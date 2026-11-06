package org.lantai.infrastructure.metadata.scrape

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.lantai.domain.model.BookMetadataAggregation
import org.lantai.domain.model.ScrapeCandidate
import org.lantai.domain.model.Series
import org.lantai.domain.model.SeriesMetadata
import org.lantai.domain.persistence.BookMetadataAggregationRepository
import org.lantai.domain.persistence.BookMetadataRepository
import org.lantai.domain.persistence.BookRepository
import org.lantai.domain.persistence.SeriesMetadataRepository
import org.lantai.domain.persistence.SeriesRepository
import org.lantai.domain.persistence.ThumbnailSeriesRepository
import org.springframework.stereotype.Service
import java.time.LocalDate

private val logger = KotlinLogging.logger {}

data class ScrapeOutcome(
  val diff: ScrapeDiff,
  val applied: ScrapeApplySummary?,
  val source: String?,
  val score: Double?,
  val message: String?,
  val status: String,
)

data class ScrapePreview(
  val seriesId: String,
  val seriesTitle: String,
  val source: String?,
  val score: Double?,
  val diff: ScrapeDiff,
  val coverUrl: String,
)

@Service
class ScrapeEngine(
  private val seriesRepository: SeriesRepository,
  private val seriesMetadataRepository: SeriesMetadataRepository,
  private val bookRepository: BookRepository,
  private val bookMetadataRepository: BookMetadataRepository,
  private val aggregationRepository: BookMetadataAggregationRepository,
  private val thumbnailsSeriesRepository: ThumbnailSeriesRepository,
  private val embeddedReader: ScrapeEmbeddedReader,
  private val registry: ScrapeSourceRegistry,
  private val rateLimiter: ScrapeRateLimiter,
  private val matcher: ScrapeMatcher,
  private val applier: ScrapeApplier,
  private val settings: ScrapeSettingsProvider,
  private val objectMapper: ObjectMapper,
) {
  fun preview(seriesId: String): ScrapePreview {
    val series = seriesRepository.findByIdOrNull(seriesId) ?: throw ScrapeException("Series $seriesId does not exist")
    val metadata = seriesMetadataRepository.findById(seriesId)
    val current = currentValues(seriesId, metadata)
    val embedded = embeddedReader.read(series)
    val found = searchCandidate(series, metadata)
    val candidate = found?.first
    val merged = merge(embedded, candidate, embedded.firstBookValues())
    val diff = compare(current, embedded.series, embedded.firstBookValues(), candidate, merged, metadata, seriesId)
    return ScrapePreview(
      seriesId = seriesId,
      seriesTitle = metadata.title.ifBlank { series.name },
      source = candidate?.source,
      score = found?.second,
      diff = diff,
      coverUrl = candidate?.coverUrl.orEmpty(),
    )
  }

  fun analyze(
    seriesId: String,
    trust: Boolean,
  ): ScrapeOutcome = finish(preview(seriesId), trust)

  fun externalAnalyze(
    seriesId: String,
    external: ScrapeValues,
    source: String,
    score: Double,
    trust: Boolean,
    scope: Set<String> = emptySet(),
  ): ScrapeOutcome {
    val series = seriesRepository.findByIdOrNull(seriesId) ?: throw ScrapeException("Series $seriesId does not exist")
    val metadata = seriesMetadataRepository.findById(seriesId)
    val current = currentValues(seriesId, metadata)
    val diff = compare(current, ScrapeValues(), external, null, external, metadata, seriesId)
    return finish(
      ScrapePreview(
        seriesId = seriesId,
        seriesTitle = metadata.title.ifBlank { series.name },
        source = source,
        score = score,
        diff = diff,
        coverUrl = external.coverUrl,
      ),
      trust,
      scope,
    )
  }

  private fun finish(
    preview: ScrapePreview,
    trust: Boolean,
    scope: Set<String> = emptySet(),
  ): ScrapeOutcome {
    val conflicts = preview.diff.pendingConflicts()
    val values = valuesOf(preview.diff)
    if (values == null) {
      return ScrapeOutcome(
        diff = preview.diff,
        applied = ScrapeApplySummary(emptyList(), emptyList(), false, null),
        source = preview.source,
        score = preview.score,
        message = "没有可补充的信息",
        status = "DONE",
      )
    }

    if (conflicts.isNotEmpty() && !trust) {
      return ScrapeOutcome(
        diff = preview.diff,
        applied = null,
        source = preview.source,
        score = preview.score,
        message = "存在 ${conflicts.size} 个字段不一致",
        status = "REVIEW",
      )
    }

    val fields =
      preview.diff.fields
        .filter { it.decision == ScrapeDecision.FILL || it.decision == ScrapeDecision.CONFLICT }
        .map { it.field }
        .toSet()
    if (fields.isEmpty()) {
      return ScrapeOutcome(
        diff = preview.diff,
        applied = ScrapeApplySummary(emptyList(), emptyList(), false, null),
        source = preview.source,
        score = preview.score,
        message = "与已有信息一致",
        status = "DONE",
      )
    }

    val summary =
      applier.applyValues(
        seriesId = preview.seriesId,
        values = values,
        fields = fields,
        source = preview.source.orEmpty(),
        score = preview.score,
        overwriteConflicts = trust,
        coverUrl = preview.coverUrl,
        scope = scope,
      )
    return ScrapeOutcome(
      diff = preview.diff,
      applied = summary,
      source = preview.source,
      score = preview.score,
      message = summary.appliedFields.joinToString("、").ifBlank { null },
      status = "DONE",
    )
  }

  fun resolve(
    seriesId: String,
    fields: Set<String>,
    scope: Set<String> = emptySet(),
  ): ScrapeOutcome {
    val preview = preview(seriesId)
    val chosen = if (fields.isEmpty()) preview.diff.fields.filter { it.decision == ScrapeDecision.FILL || it.decision == ScrapeDecision.CONFLICT } else preview.diff.fields.filter { it.field in fields }
    val values = valuesOf(chosen) ?: throw ScrapeException("Series $seriesId has no candidate to apply")
    val summary =
      applier.applyValues(
        seriesId = seriesId,
        values = values,
        fields = chosen.map { it.field }.toSet(),
        source = preview.source.orEmpty(),
        score = preview.score,
        overwriteConflicts = true,
        coverUrl = preview.coverUrl,
        scope = scope,
      )
    return ScrapeOutcome(
      diff = preview.diff,
      applied = summary,
      source = preview.source,
      score = preview.score,
      message = summary.appliedFields.joinToString("、").ifBlank { null },
      status = "DONE",
    )
  }

  fun diffFromExternal(
    seriesId: String,
    external: ScrapeValues,
  ): ScrapeDiff {
    val metadata = seriesMetadataRepository.findById(seriesId)
    val current = currentValues(seriesId, metadata)
    return compare(current, ScrapeValues(), external, null, external, metadata, seriesId)
  }

  fun resolveExternal(
    seriesId: String,
    external: ScrapeValues,
    source: String,
    score: Double?,
    fields: Set<String>,
    coverUrl: String,
    scope: Set<String> = emptySet(),
  ): ScrapeOutcome {
    val diff = diffFromExternal(seriesId, external)
    val chosen =
      if (fields.isEmpty()) {
        diff.fields.filter { it.decision == ScrapeDecision.FILL || it.decision == ScrapeDecision.CONFLICT }
      } else {
        diff.fields.filter { it.field in fields }
      }
    val values = valuesOf(chosen) ?: throw ScrapeException("Series $seriesId has no candidate to apply")
    val summary =
      applier.applyValues(
        seriesId = seriesId,
        values = values,
        fields = chosen.map { it.field }.toSet(),
        source = source.ifBlank { "agent" },
        score = score,
        overwriteConflicts = true,
        coverUrl = coverUrl,
        scope = scope,
      )
    return ScrapeOutcome(
      diff = diff,
      applied = summary,
      source = source,
      score = score,
      message = summary.appliedFields.joinToString("、").ifBlank { null },
      status = "DONE",
    )
  }

  fun serializedDiff(outcome: ScrapeOutcome): String = objectMapper.writeValueAsString(outcome.diff)

  fun parseDiff(json: String?): ScrapeDiff? =
    json?.takeIf { it.isNotBlank() }?.let {
      runCatching { objectMapper.readValue(it, ScrapeDiff::class.java) }.getOrNull()
    }

  private fun currentValues(
    seriesId: String,
    metadata: SeriesMetadata,
  ): ScrapeValues {
    val aggregation = runCatching { aggregationRepository.findByIdOrNull(seriesId) }.getOrNull() ?: BookMetadataAggregation()
    return ScrapeValues(
      title = metadata.title,
      summary = metadata.summary,
      publisher = metadata.publisher,
      region = metadata.region,
      language = metadata.language,
      ageRating = metadata.ageRating,
      genres = metadata.genres.sorted(),
      authors = aggregation.authors.map { it.name },
      releaseDate = aggregation.releaseDate,
      isbn = firstIsbn(seriesId),
      tags = aggregation.tags.sorted(),
    )
  }

  private fun firstIsbn(seriesId: String): String =
    runCatching {
      bookRepository
        .findAllBySeriesId(seriesId)
        .asSequence()
        .mapNotNull { bookMetadataRepository.findByIdOrNull(it.id)?.isbn?.takeIf { isbn -> isbn.isNotBlank() } }
        .firstOrNull()
        .orEmpty()
    }.getOrDefault("")

  private fun searchCandidate(
    series: Series,
    metadata: SeriesMetadata,
  ): Pair<ScrapeCandidate, Double>? {
    val base = metadata.title.ifBlank { series.name }.trim()
    if (base.isEmpty()) return null
    val primary = bestMatch(base)
    if (primary != null && primary.second >= ScrapeMatcher.MIN_THRESHOLD) return primary
    val fallback = bookQuery(series)
    if (fallback.isBlank() || fallback == base) return null
    val secondary = bestMatch(fallback)
    return secondary?.takeIf { it.second >= ScrapeMatcher.MIN_THRESHOLD }
  }

  private fun bookQuery(series: Series): String =
    runCatching {
      val first = bookRepository.findAllBySeriesId(series.id).sortedBy { it.number }.firstOrNull() ?: return@runCatching ""
      val title = bookMetadataRepository.findByIdOrNull(first.id)?.title.orEmpty()
      val raw = title.ifBlank { first.name.substringBeforeLast('.', first.name) }
      raw.substringBefore("作者").replace(Regex("[（(\\[【].*?[)）\\]】]"), " ").trim()
    }.getOrDefault("")

  private fun bestMatch(query: String): Pair<ScrapeCandidate, Double>? {
    var best: Pair<ScrapeCandidate, Double>? = null
    registry.enabled().forEach { source ->
      val results =
        try {
          rateLimiter.cached(source.id, query, SEARCH_LIMIT)
            ?: run {
              rateLimiter.checkQuota(source.id, ScrapePurpose.MATCH)
              rateLimiter.acquire(source.id, ScrapePurpose.MATCH)
              val found = source.search(query, SEARCH_LIMIT)
              rateLimiter.record(source.id, ScrapePurpose.MATCH)
              rateLimiter.cache(source.id, query, SEARCH_LIMIT, found)
              found
            }
        } catch (e: ScrapeQuotaExceededException) {
          logger.info { "Scrape quota exhausted for ${source.id}" }
          null
        } catch (e: ScrapeBackoffException) {
          logger.info { "Scrape source ${source.id} backing off" }
          null
        } catch (e: ScrapeCircuitOpenException) {
          logger.info { "Scrape source ${source.id} circuit open" }
          null
        } catch (e: Exception) {
          rateLimiter.noteFailure(source.id)
          logger.warn(e) { "Scrape source ${source.id} failed" }
          null
        }
      results?.forEach { candidate ->
        val score = maxOf(matcher.similarity(query, candidate.title), matcher.similarity(query, candidate.subtitle))
        if (best == null || score > best!!.second) best = candidate to score
      }
    }
    return best
  }

  private fun merge(
    embedded: EmbeddedRead,
    candidate: ScrapeCandidate?,
    bookValues: ScrapeValues,
  ): ScrapeValues {
    val c = candidate
    return ScrapeValues(
      title = embedded.series.title.ifBlank { c?.title.orEmpty() },
      summary = embedded.series.summary.ifBlank { c?.summary.orEmpty() },
      publisher = embedded.series.publisher.ifBlank { c?.publisher.orEmpty() },
      region = embedded.series.region.ifBlank { c?.region.orEmpty() },
      language = embedded.series.language,
      ageRating = null,
      genres = embedded.series.genres.ifEmpty { c?.tags.orEmpty() },
      authors = bookValues.authors.ifEmpty { c?.authors.orEmpty() },
      releaseDate = bookValues.releaseDate ?: parseDate(c?.releaseDate),
      isbn = bookValues.isbn,
      tags = bookValues.tags.ifEmpty { c?.tags.orEmpty() },
      coverUrl = c?.coverUrl.orEmpty(),
    )
  }

  private fun compare(
    current: ScrapeValues,
    embedded: ScrapeValues,
    bookValues: ScrapeValues,
    candidate: ScrapeCandidate?,
    merged: ScrapeValues,
    metadata: SeriesMetadata,
    seriesId: String,
  ): ScrapeDiff {
    val changes = mutableListOf<ScrapeFieldChange>()

    fun push(
      field: String,
      target: ScrapeTarget,
      cur: String,
      emb: String,
      cand: String,
      chosen: String,
      locked: Boolean,
    ) {
      val decision =
        when {
          locked -> ScrapeDecision.LOCKED
          cur.isBlank() && chosen.isBlank() -> ScrapeDecision.EMPTY
          chosen.isBlank() -> ScrapeDecision.SAME
          cur.isBlank() -> ScrapeDecision.FILL
          norm(cur) == norm(chosen) -> ScrapeDecision.SAME
          field == "language" && langKey(cur) == langKey(chosen) -> ScrapeDecision.SAME
          else -> ScrapeDecision.CONFLICT
        }
      changes += ScrapeFieldChange(field, target, cur, emb, cand, chosen, decision)
    }

    push("title", ScrapeTarget.SERIES, current.title, embedded.title, candidate?.title.orEmpty(), merged.title, metadata.titleLock)
    push("summary", ScrapeTarget.SERIES, current.summary, embedded.summary, candidate?.summary.orEmpty(), merged.summary, metadata.summaryLock)
    push("publisher", ScrapeTarget.SERIES, current.publisher, embedded.publisher, candidate?.publisher.orEmpty(), merged.publisher, metadata.publisherLock)
    push("region", ScrapeTarget.SERIES, current.region, embedded.region, candidate?.region.orEmpty(), merged.region, metadata.regionLock)
    push("language", ScrapeTarget.SERIES, current.language, embedded.language, "", merged.language, metadata.languageLock)
    push("genres", ScrapeTarget.SERIES, current.genres.joinToString("、"), embedded.genres.joinToString("、"), candidate?.tags.orEmpty().joinToString("、"), merged.genres.joinToString("、"), metadata.genresLock)
    push("authors", ScrapeTarget.BOOK, current.authors.joinToString("、"), bookValues.authors.joinToString("、"), candidate?.authors.orEmpty().joinToString("、"), merged.authors.joinToString("、"), false)
    push("releaseDate", ScrapeTarget.BOOK, current.releaseDate?.toString().orEmpty(), bookValues.releaseDate?.toString().orEmpty(), candidate?.releaseDate.orEmpty(), merged.releaseDate?.toString().orEmpty(), false)
    push("isbn", ScrapeTarget.BOOK, current.isbn, bookValues.isbn, "", merged.isbn, false)
    push("tags", ScrapeTarget.BOOK, current.tags.joinToString("、"), bookValues.tags.joinToString("、"), candidate?.tags.orEmpty().joinToString("、"), merged.tags.joinToString("、"), false)

    val hasCover = thumbnailsSeriesRepository.findSelectedBySeriesIdOrNull(seriesId) != null
    val coverChosen = if (hasCover) "" else merged.coverUrl
    changes +=
      ScrapeFieldChange(
        field = "cover",
        target = ScrapeTarget.COVER,
        current = if (hasCover) "已有封面" else "",
        embedded = "",
        candidate = merged.coverUrl,
        chosen = coverChosen,
        decision =
          when {
            merged.coverUrl.isBlank() -> ScrapeDecision.EMPTY
            hasCover -> ScrapeDecision.SAME
            else -> ScrapeDecision.FILL
          },
      )

    return ScrapeDiff(changes.filter { it.decision != ScrapeDecision.EMPTY })
  }

  private fun valuesOf(changes: List<ScrapeFieldChange>): ScrapeValues? {
    if (changes.isEmpty()) return null
    fun value(field: String): String = changes.firstOrNull { it.field == field }?.chosen.orEmpty()
    return ScrapeValues(
      title = value("title"),
      summary = value("summary"),
      publisher = value("publisher"),
      region = value("region"),
      language = value("language"),
      genres = split(value("genres")),
      authors = split(value("authors")),
      releaseDate = parseDate(value("releaseDate")),
      isbn = value("isbn"),
      tags = split(value("tags")),
      coverUrl = value("cover"),
    )
  }

  private fun valuesOf(diff: ScrapeDiff): ScrapeValues? = valuesOf(diff.fields)

  private fun split(value: String): List<String> =
    value.split('、', ',').map { it.trim() }.filter { it.isNotEmpty() }

  private fun parseDate(value: String?): LocalDate? {
    if (value.isNullOrBlank()) return null
    val trimmed = value.trim().take(10)
    return runCatching { LocalDate.parse(trimmed) }.getOrNull()
  }

  private fun norm(value: String): String =
    value
      .lowercase()
      .replace(Regex("[\\s\\p{Punct}，。、；：！？（）【】《》·—…]"), "")

  private fun langKey(value: String): String {
    val primary = value.trim().lowercase().replace('_', '-').substringBefore('-')
    return when (primary) {
      "zho", "chi", "zh" -> "zh"
      "eng", "en" -> "en"
      "jpn", "ja" -> "ja"
      "kor", "ko" -> "ko"
      "fra", "fre", "fr" -> "fr"
      "deu", "ger", "de" -> "de"
      "spa", "es" -> "es"
      "rus", "ru" -> "ru"
      else -> primary
    }
  }

  companion object {
    private const val SEARCH_LIMIT = 5
  }
}
