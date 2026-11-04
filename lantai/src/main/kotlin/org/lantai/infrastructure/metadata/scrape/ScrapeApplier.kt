package org.lantai.infrastructure.metadata.scrape

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.oshai.kotlinlogging.KotlinLogging
import org.lantai.domain.model.Author
import org.lantai.domain.model.Dimension
import org.lantai.domain.model.DomainEvent
import org.lantai.domain.model.MarkSelectedPreference
import org.lantai.domain.model.ScrapeCandidate
import org.lantai.domain.model.ScrapeMatch
import org.lantai.domain.model.SeriesMetadata
import org.lantai.domain.model.ThumbnailSeries
import org.lantai.domain.persistence.BookMetadataAggregationRepository
import org.lantai.domain.persistence.BookMetadataRepository
import org.lantai.domain.persistence.BookRepository
import org.lantai.domain.persistence.ScrapeMatchRepository
import org.lantai.domain.persistence.SeriesMetadataRepository
import org.lantai.domain.persistence.SeriesRepository
import org.lantai.domain.persistence.ThumbnailSeriesRepository
import org.lantai.domain.service.MetadataAggregator
import org.lantai.domain.service.SeriesLifecycle
import org.lantai.infrastructure.image.ImageAnalyzer
import org.lantai.infrastructure.mediacontainer.ContentDetector
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDate

private val logger = KotlinLogging.logger {}

data class ScrapeApplyResult(
  val matchId: String,
  val status: String,
  val appliedFields: List<String>,
  val skippedFields: List<String>,
)

data class ScrapeApplySummary(
  val appliedFields: List<String>,
  val skippedFields: List<String>,
  val coverApplied: Boolean,
  val matchId: String?,
)

@Service
class ScrapeApplier(
  private val matchRepository: ScrapeMatchRepository,
  private val seriesMetadataRepository: SeriesMetadataRepository,
  private val seriesRepository: SeriesRepository,
  private val bookRepository: BookRepository,
  private val bookMetadataRepository: BookMetadataRepository,
  private val aggregationRepository: BookMetadataAggregationRepository,
  private val metadataAggregator: MetadataAggregator,
  private val thumbnailsSeriesRepository: ThumbnailSeriesRepository,
  private val seriesLifecycle: SeriesLifecycle,
  private val contentDetector: ContentDetector,
  private val imageAnalyzer: ImageAnalyzer,
  private val eventPublisher: ApplicationEventPublisher,
  private val objectMapper: ObjectMapper,
) {
  fun apply(
    matchId: String,
    fields: Set<String>,
  ): ScrapeApplyResult {
    val match = matchRepository.findByIdOrNull(matchId) ?: throw ScrapeException("Match $matchId does not exist")
    val metadata = seriesMetadataRepository.findById(match.seriesId)
    val candidate = match.candidate
    val requested = fields.ifEmpty { SUPPORTED_FIELDS }
    val applied = mutableListOf<String>()
    val skipped = mutableListOf<String>()
    var updated = metadata

    if ("title" in requested) {
      if (metadata.titleLock) skipped += "title" else applied += "title"
      if (!metadata.titleLock && candidate.title.isNotBlank()) updated = updated.copy(title = candidate.title, titleSort = candidate.title)
    }
    if ("summary" in requested) {
      if (metadata.summaryLock) skipped += "summary" else applied += "summary"
      if (!metadata.summaryLock && candidate.summary.isNotBlank()) updated = updated.copy(summary = candidate.summary)
    }
    if ("publisher" in requested) {
      if (metadata.publisherLock) skipped += "publisher" else applied += "publisher"
      if (!metadata.publisherLock && candidate.publisher.isNotBlank()) updated = updated.copy(publisher = candidate.publisher)
    }
    if ("genres" in requested) {
      if (metadata.genresLock) skipped += "genres" else applied += "genres"
      if (!metadata.genresLock && candidate.tags.isNotEmpty()) updated = updated.copy(genres = candidate.tags.toSet())
    }

    val effective = applied - skipped.toSet()
    if (effective.isNotEmpty()) {
      seriesMetadataRepository.update(updated)
      publishUpdate(match.seriesId)
    }

    val snapshot = match.snapshot ?: objectMapper.writeValueAsString(ScrapeSnapshot.of(metadata))
    matchRepository.update(
      match.copy(
        status = if (effective.isEmpty()) match.status else ScrapeMatch.Status.APPLIED,
        snapshot = snapshot,
        appliedFields = effective.toSet(),
      ),
    )
    return ScrapeApplyResult(matchId, ScrapeMatch.Status.APPLIED.name, effective, skipped)
  }

  fun applyValues(
    seriesId: String,
    values: ScrapeValues,
    fields: Set<String>,
    source: String,
    score: Double?,
    overwriteConflicts: Boolean,
    coverUrl: String,
    scope: Set<String> = emptySet(),
  ): ScrapeApplySummary {
    val series = seriesRepository.findByIdOrNull(seriesId) ?: throw ScrapeException("Series $seriesId does not exist")
    val metadata = seriesMetadataRepository.findById(seriesId)
    val ids = bookIds(seriesId)
    val targets = if (scope.isEmpty()) ids else ids.filter { it in scope }
    val bookSnapshots =
      bookMetadataRepository.findAllByIds(ids).map {
        BookSnapshot(
          bookId = it.bookId,
          authors = it.authors.map { author -> AuthorSnapshot(author.name, author.role) },
          releaseDate = it.releaseDate,
          isbn = it.isbn,
          tags = it.tags.sorted(),
        )
      }
    val snapshot =
      ScrapeSnapshot(
        title = metadata.title,
        titleSort = metadata.titleSort,
        summary = metadata.summary,
        publisher = metadata.publisher,
        language = metadata.language,
        genres = metadata.genres.sorted(),
        ageRating = metadata.ageRating,
        books = bookSnapshots,
        coverThumbnailId = thumbnailsSeriesRepository.findSelectedBySeriesIdOrNull(seriesId)?.id,
      )

    val bookScoped = scope.isNotEmpty()
    var updated = metadata
    val applied = mutableListOf<String>()
    val skipped = mutableListOf<String>()

    fun writeSeries(
      field: String,
      locked: Boolean,
      block: (SeriesMetadata) -> SeriesMetadata,
    ) {
      if (field !in fields) return
      if (locked) {
        skipped += field
        return
      }
      updated = block(updated)
      applied += field
    }

    if (bookScoped) {
      listOf("title", "summary", "publisher", "language", "genres", "ageRating").forEach { if (it in fields) skipped += it }
    } else {
      writeSeries("title", metadata.titleLock) { it.copy(title = values.title, titleSort = values.title) }
      writeSeries("summary", metadata.summaryLock) { it.copy(summary = values.summary) }
      writeSeries("publisher", metadata.publisherLock) { it.copy(publisher = values.publisher) }
      writeSeries("language", metadata.languageLock) { it.copy(language = values.language) }
      writeSeries("genres", metadata.genresLock) { it.copy(genres = values.genres.toSet()) }
      if ("ageRating" in fields && values.ageRating != null) {
        if (metadata.ageRatingLock) skipped += "ageRating" else { updated = updated.copy(ageRating = values.ageRating); applied += "ageRating" }
      }
    }

    if (updated !== metadata) {
      seriesMetadataRepository.update(updated)
      publishUpdate(seriesId)
    }

    val bookFields =
      (
        listOf("authors", "releaseDate", "isbn", "tags") +
          (if (bookScoped) listOf("title", "summary", "publisher", "genres", "language") else emptyList())
      ).filter { it in fields }
    if (bookFields.isNotEmpty()) {
      var changed = false
      targets.forEach { bookId ->
        val current = bookMetadataRepository.findByIdOrNull(bookId) ?: return@forEach
        var next = current
        if ("authors" in bookFields && values.authors.isNotEmpty() && !current.authorsLock) {
          if (current.authors.isEmpty() || overwriteConflicts) next = next.copy(authors = values.authors.map { Author(it, "writer") })
        }
        if ("releaseDate" in bookFields && values.releaseDate != null && !current.releaseDateLock) {
          if (current.releaseDate == null || overwriteConflicts) next = next.copy(releaseDate = values.releaseDate)
        }
        if ("isbn" in bookFields && values.isbn.isNotBlank() && !current.isbnLock) {
          if (current.isbn.isBlank() || overwriteConflicts) next = next.copy(isbn = values.isbn)
        }
        if ("tags" in bookFields && values.tags.isNotEmpty() && !current.tagsLock) {
          if (current.tags.isEmpty() || overwriteConflicts) next = next.copy(tags = current.tags + values.tags)
        }
        if ("title" in bookFields && values.title.isNotBlank() && !current.titleLock) {
          if (current.title.isBlank() || overwriteConflicts) next = next.copy(title = values.title)
        }
        if ("summary" in bookFields && values.summary.isNotBlank() && !current.summaryLock) {
          if (current.summary.isBlank() || overwriteConflicts) next = next.copy(summary = values.summary)
        }
        if ("publisher" in bookFields && values.publisher.isNotBlank() && !current.publisherLock) {
          if (current.publisher.isBlank() || overwriteConflicts) next = next.copy(publisher = values.publisher)
        }
        if ("genres" in bookFields && values.genres.isNotEmpty() && !current.genresLock) {
          if (current.genres.isEmpty() || overwriteConflicts) next = next.copy(genres = values.genres.toSet())
        }
        if ("language" in bookFields && values.language.isNotBlank() && !current.languageLock) {
          if (current.language.isBlank() || overwriteConflicts) next = next.copy(language = values.language)
        }
        if (next !== current) {
          bookMetadataRepository.update(next)
          changed = true
        }
      }
      if (changed) {
        bookFields.forEach { if (it !in applied) applied += it }
        runCatching { aggregationRepository.update(metadataAggregator.aggregate(bookMetadataRepository.findAllByIds(ids))) }
        publishUpdate(seriesId)
      }
    }

    val coverApplied =
      when {
        "cover" !in fields -> false
        bookScoped -> {
          skipped += "cover"
          false
        }
        coverUrl.isNotBlank() -> applyCover(seriesId, coverUrl)
        else -> false
      }
    if (coverApplied) applied += "cover"

    val matchId =
      runCatching {
        val candidate =
          ScrapeCandidate(
            source = source.ifBlank { "agent" },
            sourceId = seriesId,
            title = values.title,
            subtitle = "",
            authors = values.authors,
            publisher = values.publisher,
            releaseDate = values.releaseDate?.toString().orEmpty(),
            summary = values.summary,
            coverUrl = coverUrl,
            tags = values.genres,
          )
        val match =
          ScrapeMatch(
            seriesId = seriesId,
            seriesTitle = metadata.title.ifBlank { series.name },
            source = source.ifBlank { "agent" },
            sourceId = seriesId,
            score = score ?: 1.0,
            status = ScrapeMatch.Status.APPLIED,
            candidate = candidate,
            snapshot = objectMapper.writeValueAsString(snapshot),
            appliedFields = applied.toSet(),
          )
        matchRepository.insert(match)
        match.id
      }.getOrNull()

    return ScrapeApplySummary(appliedFields = applied, skippedFields = skipped, coverApplied = coverApplied, matchId = matchId)
  }

  fun applyBookValues(
    seriesId: String,
    choices: List<ScrapeBookChoice>,
    source: String,
    score: Double?,
    scope: Set<String> = emptySet(),
  ): ScrapeApplySummary {
    val series = seriesRepository.findByIdOrNull(seriesId) ?: throw ScrapeException("Series $seriesId does not exist")
    val metadata = seriesMetadataRepository.findById(seriesId)
    val ids = bookIds(seriesId)
    val allowed = if (scope.isEmpty()) ids.toSet() else ids.toSet().intersect(scope)
    val snapshot =
      ScrapeSnapshot(
        title = metadata.title,
        titleSort = metadata.titleSort,
        summary = metadata.summary,
        publisher = metadata.publisher,
        language = metadata.language,
        genres = metadata.genres.sorted(),
        ageRating = metadata.ageRating,
        books = bookSnapshots(ids),
      )
    val applied = linkedSetOf<String>()
    val skipped = mutableListOf<String>()
    var changed = false

    choices.filter { it.bookId.isNotBlank() && it.bookId in allowed }.forEach { choice ->
      val current = bookMetadataRepository.findByIdOrNull(choice.bookId) ?: return@forEach
      val fields = choice.fields.toSet()
      var next = current
      var touched = false

      fun write(
        name: String,
        locked: Boolean,
        block: () -> Unit,
      ) {
        if (name !in fields) return
        if (locked) {
          skipped += name
          return
        }
        block()
        applied += name
        touched = true
      }

      write("title", current.titleLock) { if (choice.title.isNotBlank()) next = next.copy(title = choice.title.trim()) }
      write("number", current.numberLock) { if (choice.number.isNotBlank()) next = next.copy(number = choice.number.trim()) }
      write("summary", current.summaryLock) { if (choice.summary.isNotBlank()) next = next.copy(summary = choice.summary.trim()) }
      write("publisher", current.publisherLock) { if (choice.publisher.isNotBlank()) next = next.copy(publisher = choice.publisher.trim()) }
      write("language", current.languageLock) { if (choice.language.isNotBlank()) next = next.copy(language = choice.language.trim()) }
      write("genres", current.genresLock) { if (choice.genres.isNotEmpty()) next = next.copy(genres = choice.genres.toSet()) }
      write("authors", current.authorsLock) {
        if (choice.authors.isNotEmpty()) next = next.copy(authors = choice.authors.map { Author(it, "writer") })
      }
      write("isbn", current.isbnLock) { if (choice.isbn.isNotBlank()) next = next.copy(isbn = choice.isbn.trim()) }
      write("tags", current.tagsLock) {
        if (choice.tags.isNotEmpty()) next = next.copy(tags = current.tags + choice.tags)
      }
      val date = parseIsoDate(choice.releaseDate)
      if (date != null) write("releaseDate", current.releaseDateLock) { next = next.copy(releaseDate = date) }

      if (touched && next !== current) {
        bookMetadataRepository.update(next)
        changed = true
      }
    }

    if (changed) {
      runCatching { aggregationRepository.update(metadataAggregator.aggregate(bookMetadataRepository.findAllByIds(ids))) }
      publishUpdate(seriesId)
    }

    val matchId =
      if (changed) {
        runCatching {
          val match =
            ScrapeMatch(
              seriesId = seriesId,
              seriesTitle = metadata.title.ifBlank { series.name },
              source = source.ifBlank { "manual" },
              sourceId = seriesId,
              score = score ?: 1.0,
              status = ScrapeMatch.Status.APPLIED,
              candidate = ScrapeCandidate(source = source.ifBlank { "manual" }, sourceId = seriesId, title = ""),
              snapshot = objectMapper.writeValueAsString(snapshot),
              appliedFields = applied.toSet(),
            )
          matchRepository.insert(match)
          match.id
        }.getOrNull()
      } else {
        null
      }

    return ScrapeApplySummary(appliedFields = applied.toList(), skippedFields = skipped.distinct(), coverApplied = false, matchId = matchId)
  }

  private fun bookSnapshots(ids: List<String>): List<BookSnapshot> =
    bookMetadataRepository.findAllByIds(ids).map {
      BookSnapshot(
        bookId = it.bookId,
        authors = it.authors.map { author -> AuthorSnapshot(author.name, author.role) },
        releaseDate = it.releaseDate,
        isbn = it.isbn,
        tags = it.tags.sorted(),
        publisher = it.publisher,
        genres = it.genres.sorted(),
        language = it.language,
      )
    }

  private fun parseIsoDate(value: String): LocalDate? =
    value.trim().take(10).takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

  fun skip(matchId: String): ScrapeApplyResult {    val match = matchRepository.findByIdOrNull(matchId) ?: throw ScrapeException("Match $matchId does not exist")
    matchRepository.update(match.copy(status = ScrapeMatch.Status.SKIPPED))
    return ScrapeApplyResult(matchId, ScrapeMatch.Status.SKIPPED.name, emptyList(), emptyList())
  }

  fun rollback(matchId: String): ScrapeApplyResult {
    val match = matchRepository.findByIdOrNull(matchId) ?: throw ScrapeException("Match $matchId does not exist")
    val snapshotJson = match.snapshot ?: throw ScrapeException("Match $matchId has no snapshot to roll back to")
    val snapshot = objectMapper.readValue<ScrapeSnapshot>(snapshotJson)
    val metadata = seriesMetadataRepository.findById(match.seriesId)
    val restored =
      metadata.copy(
        title = snapshot.title.ifBlank { metadata.title },
        titleSort = snapshot.titleSort.ifBlank { snapshot.title },
        summary = snapshot.summary,
        publisher = snapshot.publisher,
        language = snapshot.language,
        genres = snapshot.genres.toSet(),
        ageRating = snapshot.ageRating,
      )
    seriesMetadataRepository.update(restored)

    snapshot.books.forEach { book ->
      val current = bookMetadataRepository.findByIdOrNull(book.bookId) ?: return@forEach
      bookMetadataRepository.update(
        current.copy(
          authors = book.authors.map { Author(it.name, it.role) },
          releaseDate = book.releaseDate,
          isbn = book.isbn,
          tags = book.tags.toSet(),
          publisher = book.publisher,
          genres = book.genres.toSet(),
          language = book.language,
        ),
      )
    }
    if (snapshot.books.isNotEmpty()) {
      runCatching {
        val ids = bookIds(match.seriesId)
        aggregationRepository.update(metadataAggregator.aggregate(bookMetadataRepository.findAllByIds(ids)))
      }
    }
    snapshot.coverThumbnailId?.let { id ->
      runCatching {
        thumbnailsSeriesRepository.findByIdOrNull(id)?.let { thumbnailsSeriesRepository.markSelected(it) }
      }
    }
    publishUpdate(match.seriesId)
    matchRepository.update(match.copy(status = ScrapeMatch.Status.PENDING, snapshot = null, appliedFields = emptySet()))
    return ScrapeApplyResult(matchId, ScrapeMatch.Status.PENDING.name, emptyList(), emptyList())
  }

  private fun applyCover(
    seriesId: String,
    coverUrl: String,
  ): Boolean {
    val bytes =
      runCatching { download(coverUrl) }
        .onFailure { logger.warn(it) { "Failed to download cover for $seriesId" } }
        .getOrNull()
        ?: return false
    if (bytes.isEmpty() || bytes.size > MAX_COVER_BYTES) return false
    val mediaType =
      runCatching { contentDetector.detectMediaType(bytes.inputStream().buffered()) }.getOrNull()
        ?: return false
    if (!contentDetector.isImage(mediaType)) return false
    val dimension =
      runCatching { imageAnalyzer.getDimension(bytes.inputStream().buffered()) }.getOrNull() ?: Dimension(0, 0)
    val added =
      runCatching {
        seriesLifecycle.addThumbnailForSeries(
          ThumbnailSeries(
            seriesId = seriesId,
            thumbnail = bytes,
            type = ThumbnailSeries.Type.USER_UPLOADED,
            fileSize = bytes.size.toLong(),
            mediaType = mediaType,
            dimension = dimension,
          ),
          MarkSelectedPreference.YES,
        )
      }.onFailure { logger.warn(it) { "Failed to attach cover for $seriesId" } }.getOrNull()
    return added != null
  }

  private fun download(url: String): ByteArray {
    val client =
      HttpClient
        .newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
    val request =
      HttpRequest
        .newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(20))
        .header("User-Agent", "Mozilla/5.0 (compatible; Lantai/0.3)")
        .GET()
        .build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
    if (response.statusCode() !in 200..299) throw ScrapeException("Cover download failed with ${response.statusCode()}")
    return response.body()
  }

  private fun bookIds(seriesId: String): List<String> = bookRepository.findAllBySeriesId(seriesId).map { it.id }

  private fun publishUpdate(seriesId: String) {
    seriesRepository.findByIdOrNull(seriesId)?.let { eventPublisher.publishEvent(DomainEvent.SeriesUpdated(it)) }
  }

  data class AuthorSnapshot(
    val name: String = "",
    val role: String = "",
  )

  data class BookSnapshot(
    val bookId: String = "",
    val authors: List<AuthorSnapshot> = emptyList(),
    val releaseDate: LocalDate? = null,
    val isbn: String = "",
    val tags: List<String> = emptyList(),
    val publisher: String = "",
    val genres: List<String> = emptyList(),
    val language: String = "",
  )

  data class ScrapeSnapshot(
    val title: String = "",
    val titleSort: String = "",
    val summary: String = "",
    val publisher: String = "",
    val language: String = "",
    val genres: List<String> = emptyList(),
    val ageRating: Int? = null,
    val books: List<BookSnapshot> = emptyList(),
    val coverThumbnailId: String? = null,
  ) {
    companion object {
      fun of(metadata: SeriesMetadata) =
        ScrapeSnapshot(
          title = metadata.title,
          titleSort = metadata.titleSort,
          summary = metadata.summary,
          publisher = metadata.publisher,
          language = metadata.language,
          genres = metadata.genres.sorted(),
          ageRating = metadata.ageRating,
        )
    }
  }

  companion object {
    val SUPPORTED_FIELDS = setOf("title", "summary", "publisher", "genres")
    private const val MAX_COVER_BYTES = 12 * 1024 * 1024
  }
}
