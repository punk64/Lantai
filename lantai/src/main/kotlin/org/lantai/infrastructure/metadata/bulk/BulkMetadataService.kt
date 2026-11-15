package org.lantai.infrastructure.metadata.bulk

import com.fasterxml.jackson.databind.ObjectMapper
import org.lantai.domain.model.BookMetadata
import org.lantai.domain.model.ScrapeCandidate
import org.lantai.domain.model.ScrapeMatch
import org.lantai.domain.model.SeriesMetadata
import org.lantai.domain.persistence.BookMetadataAggregationRepository
import org.lantai.domain.persistence.BookMetadataRepository
import org.lantai.domain.persistence.BookRepository
import org.lantai.domain.persistence.ScrapeMatchRepository
import org.lantai.domain.persistence.SeriesMetadataRepository
import org.lantai.domain.persistence.SeriesRepository
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Service
class BulkMetadataService(
  private val seriesRepository: SeriesRepository,
  private val seriesMetadataRepository: SeriesMetadataRepository,
  private val bookRepository: BookRepository,
  private val bookMetadataRepository: BookMetadataRepository,
  private val bookMetadataAggregationRepository: BookMetadataAggregationRepository,
  private val scrapeMatchRepository: ScrapeMatchRepository,
  private val objectMapper: ObjectMapper,
) {
  fun sheet(seriesIds: List<String>): String {
    val rows =
      seriesIds.mapIndexedNotNull { index, id ->
        val series = seriesRepository.findByIdOrNull(id) ?: return@mapIndexedNotNull null
        BulkSheetRow(index + 1, series.id, series.name, series.bookCount)
      }
    return BulkMetadataParser.render(rows, stamp())
  }

  fun pendingSheet(
    limit: Int,
    offset: Int,
    onlyMissing: Boolean,
  ): String {
    val all = seriesRepository.findAll().filter { it.deletedDate == null }
    val filtered = if (onlyMissing) all.filter { needsFill(it.id) } else all
    val page = filtered.sortedBy { it.name }.drop(offset).take(limit)
    val rows = page.mapIndexed { index, series -> BulkSheetRow(offset + index + 1, series.id, series.name, series.bookCount) }
    return BulkMetadataParser.render(rows, stamp())
  }

  fun pendingCount(onlyMissing: Boolean): Int {
    val all = seriesRepository.findAll().filter { it.deletedDate == null }
    return if (onlyMissing) all.count { needsFill(it.id) } else all.size
  }

  fun submit(
    text: String,
    dryRun: Boolean,
  ): BulkResult {
    val entries = BulkMetadataParser.parse(text)
    if (entries.isEmpty()) return BulkResult(dryRun, 0, 0, 0, 0, emptyList())
    val items = entries.map { applyEntry(it, dryRun) }
    val applied = items.count { it.ok && it.changes.any { change -> change.applied && !change.locked } }
    val failed = items.count { !it.ok }
    return BulkResult(
      dryRun = dryRun,
      total = items.size,
      applied = applied,
      skipped = items.size - applied - failed,
      failed = failed,
      items = items,
    )
  }

  private fun applyEntry(
    entry: BulkEntry,
    dryRun: Boolean,
  ): BulkItemResult {
    val series =
      seriesRepository.findByIdOrNull(entry.seriesId)
        ?: return BulkItemResult(entry.seriesId, entry.seriesId, ok = false, notes = listOf("系列不存在，已跳过"))
    val metadata =
      seriesMetadataRepository.findByIdOrNull(entry.seriesId)
        ?: return BulkItemResult(entry.seriesId, series.name, ok = false, notes = listOf("缺少元数据记录，已跳过"))

    val changes = mutableListOf<BulkFieldChange>()
    val notes = mutableListOf<String>()
    var updated = metadata

    fun applyText(
      field: String,
      current: String,
      incoming: String?,
      locked: Boolean,
      assign: (String) -> Unit,
    ) {
      if (incoming.isNullOrBlank()) return
      if (locked) {
        changes += BulkFieldChange(field, current, incoming, true, false)
        return
      }
      if (incoming == current) return
      assign(incoming)
      changes += BulkFieldChange(field, current, incoming, false, true)
    }

    applyText(BulkMetadataParser.TITLE, metadata.title, entry.title, metadata.titleLock) {
      updated = updated.copy(title = it, titleSort = it)
    }
    applyText(BulkMetadataParser.PUBLISHER, metadata.publisher, entry.publisher, metadata.publisherLock) {
      updated = updated.copy(publisher = it)
    }
    applyText(BulkMetadataParser.LANGUAGE, metadata.language, entry.language, metadata.languageLock) {
      updated = updated.copy(language = it)
    }
    applyText(BulkMetadataParser.SUMMARY, metadata.summary, entry.summary, metadata.summaryLock) {
      updated = updated.copy(summary = it)
    }

    entry.status?.let { raw ->
      val normalized = BulkMetadataParser.normalizeStatus(raw)
      when {
        normalized == null -> notes += "状态无法识别：$raw"
        metadata.statusLock -> changes += BulkFieldChange(BulkMetadataParser.STATUS, metadata.status.name, normalized, true, false)
        normalized != metadata.status.name -> {
          updated = updated.copy(status = SeriesMetadata.Status.valueOf(normalized))
          changes += BulkFieldChange(BulkMetadataParser.STATUS, metadata.status.name, normalized, false, true)
        }
      }
    }

    entry.genres?.let { list ->
      val incoming = list.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
      if (incoming.isEmpty()) return@let
      when {
        metadata.genresLock ->
          changes += BulkFieldChange(
            BulkMetadataParser.GENRES,
            metadata.genres.joinToString(", "),
            incoming.joinToString(", "),
            true,
            false,
          )
        incoming != metadata.genres -> {
          val before = metadata.genres.joinToString(", ")
          updated = updated.copy(genres = incoming)
          changes += BulkFieldChange(BulkMetadataParser.GENRES, before, incoming.joinToString(", "), false, true)
        }
      }
    }

    var bookUpdated: BookMetadata? = null
    entry.year?.let { year ->
      val first = seriesBookMetadatas(series.id).minByOrNull { it.numberSort }
      when {
        first == null -> notes += "系列内没有册，年份未写入"
        first.releaseDateLock ->
          changes += BulkFieldChange(BulkMetadataParser.YEAR, first.releaseDate?.toString() ?: "", year.toString(), true, false)
        else -> {
          val target = LocalDate.of(year, 1, 1)
          if (first.releaseDate != target) {
            bookUpdated = first.copy(releaseDate = target)
            changes += BulkFieldChange(BulkMetadataParser.YEAR, first.releaseDate?.toString() ?: "", target.toString(), false, true)
          }
        }
      }
    }

    val effective = changes.filter { it.applied && !it.locked }
    if (!dryRun && effective.isNotEmpty()) {
      seriesMetadataRepository.update(updated)
      bookUpdated?.let { bookMetadataRepository.update(it) }
      recomputeAggregation(series.id)
      saveSnapshot(series.id, series.name, metadata, entry, effective)
    }

    return BulkItemResult(series.id, series.name, ok = true, changes = changes, notes = notes)
  }

  private fun needsFill(seriesId: String): Boolean {
    val metadata = seriesMetadataRepository.findByIdOrNull(seriesId) ?: return true
    return metadata.publisher.isBlank() || metadata.genres.isEmpty() || metadata.summary.isBlank()
  }

  private fun seriesBookMetadatas(seriesId: String): Collection<BookMetadata> {
    val bookIds = bookRepository.findAllBySeriesId(seriesId).map { it.id }
    if (bookIds.isEmpty()) return emptyList()
    return bookMetadataRepository.findAllByIds(bookIds)
  }

  private fun recomputeAggregation(seriesId: String) {
    val aggregation = bookMetadataAggregationRepository.findByIdOrNull(seriesId) ?: return
    val earliest = seriesBookMetadatas(seriesId).mapNotNull { it.releaseDate }.minOrNull()
    if (aggregation.releaseDate != earliest) {
      bookMetadataAggregationRepository.update(aggregation.copy(releaseDate = earliest))
    }
  }

  private fun saveSnapshot(
    seriesId: String,
    seriesName: String,
    before: SeriesMetadata,
    entry: BulkEntry,
    changes: List<BulkFieldChange>,
  ) {
    val snapshot =
      mapOf(
        "title" to before.title,
        "summary" to before.summary,
        "publisher" to before.publisher,
        "genres" to before.genres.sorted(),
      )
    val candidate =
      ScrapeCandidate(
        source = SOURCE,
        sourceId = entry.seriesId,
        title = entry.title ?: before.title,
        summary = entry.summary ?: before.summary,
        publisher = entry.publisher ?: before.publisher,
        releaseDate = entry.year?.let { "$it-01-01" } ?: "",
        tags = entry.genres ?: before.genres.toList(),
      )
    scrapeMatchRepository.insert(
      ScrapeMatch(
        seriesId = seriesId,
        seriesTitle = seriesName,
        source = SOURCE,
        sourceId = entry.seriesId,
        score = 1.0,
        status = ScrapeMatch.Status.APPLIED,
        candidate = candidate,
        snapshot = objectMapper.writeValueAsString(snapshot),
        appliedFields = changes.map { it.field }.toSet(),
      ),
    )
  }

  private fun stamp(): String = STAMP.format(LocalDateTime.now())

  companion object {
    private const val SOURCE = "BULK"
    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
  }
}
