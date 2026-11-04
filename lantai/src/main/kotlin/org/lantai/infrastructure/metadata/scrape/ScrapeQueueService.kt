package org.lantai.infrastructure.metadata.scrape

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.lantai.domain.model.DomainEvent
import org.lantai.domain.model.ScrapeInboxEntry
import org.lantai.domain.model.ScrapeQueueItem
import org.lantai.domain.persistence.BookRepository
import org.lantai.domain.persistence.ScrapeInboxRepository
import org.lantai.domain.persistence.ScrapeQueueBrief
import org.lantai.domain.persistence.ScrapeQueueRepository
import org.lantai.domain.persistence.SeriesMetadataRepository
import org.lantai.domain.persistence.SeriesRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger {}

data class ScrapeQueueResult(
  val fields: List<String> = emptyList(),
  val cover: Boolean = false,
)

data class ScrapeQueueDto(
  val id: String,
  val seriesId: String,
  val seriesTitle: String,
  val status: String,
  val origin: String,
  val libraryId: String?,
  val matchId: String?,
  val score: Double?,
  val source: String?,
  val message: String?,
  val attempt: Int,
  val progress: Int,
  val result: ScrapeQueueResult?,
  val conflictCount: Int,
  val bookCount: Int,
  val createdDate: String,
  val lastModifiedDate: String,
  val finishedDate: String?,
)

data class ScrapeQueueCounts(
  val total: Int,
  val queued: Int,
  val running: Int,
  val review: Int,
  val failed: Int,
)

data class ScrapeQueuePage(
  val total: Int,
  val offset: Int,
  val limit: Int,
  val counts: ScrapeQueueCounts,
  val items: List<ScrapeQueueDto>,
)

data class ScrapeQueueEnqueueResult(
  val added: Int,
  val skipped: Int,
  val running: Boolean,
)

@Service
class ScrapeQueueService(
  private val queueRepository: ScrapeQueueRepository,
  private val seriesRepository: SeriesRepository,
  private val seriesMetadataRepository: SeriesMetadataRepository,
  private val bookRepository: BookRepository,
  private val bookCatalog: ScrapeBookCatalog,
  private val engine: ScrapeEngine,
  private val applier: ScrapeApplier,
  private val inboxRepository: ScrapeInboxRepository,
  private val settings: ScrapeSettingsProvider,
  private val eventPublisher: ApplicationEventPublisher,
  private val objectMapper: ObjectMapper,
) {
  private val executor =
    Executors.newSingleThreadExecutor { runnable ->
      Thread(runnable, "lantai-scrape-queue").apply { isDaemon = true }
    }
  private val busy = AtomicBoolean(false)
  private val paused = AtomicBoolean(false)

  fun enqueueSeries(
    seriesIds: List<String>,
    origin: ScrapeQueueItem.Origin,
  ): ScrapeQueueEnqueueResult = enqueue(seriesIds.distinct().associateWith { emptyList() }, origin)

  fun enqueueBooks(
    bookIds: List<String>,
    origin: ScrapeQueueItem.Origin,
  ): ScrapeQueueEnqueueResult {
    val books = runCatching { bookRepository.findAllByIds(bookIds.distinct()) }.getOrDefault(emptyList())
    val scope = books.groupBy { it.seriesId }.mapValues { (_, list) -> list.map { it.id }.distinct() }
    return enqueue(scope, origin)
  }

  private fun enqueue(
    scopeBySeries: Map<String, List<String>>,
    origin: ScrapeQueueItem.Origin,
  ): ScrapeQueueEnqueueResult {
    var added = 0
    var skipped = 0
    scopeBySeries.forEach { (seriesId, scope) ->
      val series = seriesRepository.findByIdOrNull(seriesId)
      if (series == null) {
        skipped++
        return@forEach
      }
      val title =
        seriesMetadataRepository.findByIdOrNull(seriesId)?.title?.takeIf { it.isNotBlank() } ?: series.name
      val existing = queueRepository.findBySeriesId(seriesId)
      if (existing == null) {
        queueRepository.insert(
          ScrapeQueueItem(
            seriesId = seriesId,
            seriesTitle = title,
            origin = origin,
            libraryId = series.libraryId,
            bookIds = scope.distinct(),
          ),
        )
        added++
        return@forEach
      }
      val merged =
        when {
          existing.bookIds.isEmpty() -> existing.bookIds
          scope.isEmpty() -> emptyList()
          else -> (existing.bookIds + scope).distinct()
        }
      if (merged.size == existing.bookIds.size) {
        skipped++
        return@forEach
      }
      queueRepository.update(existing.copy(bookIds = merged, seriesTitle = title))
      added++
    }
    if (added > 0) publishChanged(null)
    return ScrapeQueueEnqueueResult(added = added, skipped = skipped, running = busy.get())
  }

  fun enqueueLibrary(libraryId: String): ScrapeQueueEnqueueResult {
    val ids = seriesRepository.findAllByLibraryId(libraryId).map { it.id }
    return enqueueSeries(ids, ScrapeQueueItem.Origin.LIBRARY)
  }

  fun enqueueMissing(limit: Int): ScrapeQueueEnqueueResult {
    val ids =
      seriesRepository
        .findAll()
        .asSequence()
        .filter { it.deletedDate == null }
        .filter { needsFill(it.id) }
        .take(limit.coerceIn(1, MAX_ENQUEUE))
        .map { it.id }
        .toList()
    return enqueueSeries(ids, ScrapeQueueItem.Origin.MULTI)
  }

  fun page(
    status: ScrapeQueueItem.Status?,
    offset: Int,
    limit: Int,
  ): ScrapeQueuePage {
    val safeLimit = limit.coerceIn(1, MAX_PAGE_SIZE)
    val safeOffset = offset.coerceAtLeast(0)
    val pageItems = queueRepository.findPage(status, safeOffset, safeLimit)
    val brief = queueRepository.findAllBrief()
    val seriesBookCounts = bookCountMap(brief.map { it.seriesId })
    fun scopeSize(row: ScrapeQueueBrief) = if (row.bookIds.isNotEmpty()) row.bookIds.size else seriesBookCounts[row.seriesId] ?: 0
    val items = pageItems.map { it.toDto(if (it.bookIds.isNotEmpty()) it.bookIds.size else seriesBookCounts[it.seriesId] ?: 0) }
    val byStatus = brief.groupBy { it.status }
    fun statusCount(state: ScrapeQueueItem.Status) = byStatus[state].orEmpty().sumOf { scopeSize(it) }
    val counts =
      ScrapeQueueCounts(
        total = brief.sumOf { scopeSize(it) },
        queued = statusCount(ScrapeQueueItem.Status.QUEUED),
        running = statusCount(ScrapeQueueItem.Status.RUNNING),
        review = statusCount(ScrapeQueueItem.Status.REVIEW),
        failed = statusCount(ScrapeQueueItem.Status.FAILED),
      )
    return ScrapeQueuePage(
      total = if (status == null) brief.size else queueRepository.countByStatus(status).toInt(),
      offset = safeOffset,
      limit = safeLimit,
      counts = counts,
      items = items,
    )
  }

  fun start(): Int {
    if (!busy.compareAndSet(false, true)) return 0
    paused.set(false)
    executor.submit {
      try {
        queueRepository.findRunning().forEach { queueRepository.update(it.copy(status = ScrapeQueueItem.Status.QUEUED)) }
        while (!paused.get()) {
          val batch = queueRepository.findQueued(settings.batchSize)
          if (batch.isEmpty()) break
          batch.forEach { process(it.id) }
        }
      } catch (e: Exception) {
        logger.warn(e) { "Scrape queue run aborted" }
      } finally {
        busy.set(false)
        publishChanged(null)
      }
    }
    return queueRepository.countByStatus(ScrapeQueueItem.Status.QUEUED).toInt()
  }

  fun pause(): Int {
    paused.set(true)
    return queueRepository.countByStatus(ScrapeQueueItem.Status.QUEUED).toInt()
  }

  fun isRunning(): Boolean = busy.get()

  fun retry(id: String): ScrapeQueueDto {
    val item = queueRepository.findByIdOrNull(id) ?: throw NoSuchElementException("Queue item $id does not exist")
    val reset =
      item.copy(
        status = ScrapeQueueItem.Status.QUEUED,
        attempt = item.attempt + 1,
        matchId = null,
        score = null,
        source = null,
        message = null,
        progress = 0,
        diff = null,
        result = null,
        lastError = null,
        finishedDate = null,
      )
    queueRepository.update(reset)
    publishChanged(item.seriesId)
    return reset.toDto(scopeCount(reset))
  }

  fun remove(id: String) {
    val item = queueRepository.findByIdOrNull(id) ?: return
    queueRepository.deleteById(id)
    publishChanged(item.seriesId)
  }

  fun clear(scope: String): Int {
    val doomed =
      when (scope.lowercase()) {
        "queued" -> listOf(ScrapeQueueItem.Status.QUEUED, ScrapeQueueItem.Status.RUNNING)
        "review" -> listOf(ScrapeQueueItem.Status.REVIEW)
        "failed" -> listOf(ScrapeQueueItem.Status.FAILED)
        "all" -> ScrapeQueueItem.Status.values().toList()
        else -> emptyList()
      }
    if (doomed.isEmpty()) return 0
    val before = doomed.sumOf { queueRepository.countByStatus(it) }.toInt()
    queueRepository.deleteByStatuses(doomed)
    publishChanged(null)
    return before
  }

  private fun externalValues(id: String): Pair<ScrapeInboxEntry, ScrapeValues>? {
    val entry = inboxRepository.findLatestByQueueId(id) ?: return null
    if (entry.payload.isBlank()) return null
    val values = runCatching { ScrapePayloadParser.parse(objectMapper, entry.payload) }.getOrNull() ?: return null
    return entry to values
  }

  fun preview(id: String): ScrapePreview {
    val item = queueRepository.findByIdOrNull(id) ?: throw NoSuchElementException("Queue item $id does not exist")
    val external = externalValues(id)
    if (external != null) {
      return ScrapePreview(
        seriesId = item.seriesId,
        seriesTitle = item.seriesTitle,
        source = external.first.source,
        score = item.score,
        diff = engine.diffFromExternal(item.seriesId, external.second),
        coverUrl = external.second.coverUrl,
      )
    }
    return engine.preview(item.seriesId)
  }

  fun find(id: String): ScrapeQueueDto {
    val item = queueRepository.findByIdOrNull(id) ?: throw NoSuchElementException("Queue item $id does not exist")
    return item.toDto(scopeCount(item))
  }

  fun books(id: String): List<ScrapeBookDto> {
    val item = queueRepository.findByIdOrNull(id) ?: throw NoSuchElementException("Queue item $id does not exist")
    val all = bookCatalog.list(item.seriesId)
    return if (item.bookIds.isEmpty()) all else all.filter { it.bookId in item.bookIds }
  }

  fun resolve(
    id: String,
    fields: Set<String>,
    books: List<ScrapeBookChoice> = emptyList(),
  ): ScrapeQueueDto? {
    val item = queueRepository.findByIdOrNull(id) ?: throw NoSuchElementException("Queue item $id does not exist")
    var touched = false
    val scope = item.bookIds.toSet()
    if (books.isNotEmpty()) {
      val summary = applier.applyBookValues(item.seriesId, books, item.source.orEmpty().ifBlank { "manual" }, item.score, scope)
      touched = summary.appliedFields.isNotEmpty()
    }
    if (fields.isNotEmpty() || books.isEmpty()) {
      val external = externalValues(id)
      val outcome =
        if (external != null) {
          engine.resolveExternal(item.seriesId, external.second, external.first.source.orEmpty(), item.score, fields, external.second.coverUrl, scope)
        } else {
          engine.resolve(item.seriesId, fields, scope)
        }
      if (outcome.applied != null && outcome.applied.appliedFields.isNotEmpty()) touched = true
    }
    if (touched) {
      queueRepository.deleteById(id)
      publishChanged(item.seriesId)
      return null
    }
    return queueRepository.findByIdOrNull(id)?.toDto(scopeCount(item))
  }

  private fun process(id: String) {
    val item = queueRepository.findByIdOrNull(id) ?: return
    queueRepository.update(item.copy(status = ScrapeQueueItem.Status.RUNNING, progress = 5, message = null, lastError = null))
    publishChanged(item.seriesId)
    try {
      val outcome =
        if (item.bookIds.isEmpty()) {
          engine.analyze(item.seriesId, settings.trustResult)
        } else {
          val preview = engine.preview(item.seriesId)
          ScrapeOutcome(
            diff = preview.diff,
            applied = null,
            source = preview.source,
            score = preview.score,
            message = "按册刮削：核对后选择要写入的册",
            status = "REVIEW",
          )
        }
      if (outcome.status == "DONE") {
        queueRepository.deleteById(item.id)
        publishChanged(item.seriesId)
        logger.info { "Scraped ${item.seriesTitle}: ${outcome.applied?.appliedFields ?: emptyList<String>()}" }
      } else {
        queueRepository.update(
          item.copy(
            status = ScrapeQueueItem.Status.REVIEW,
            progress = 100,
            diff = engine.serializedDiff(outcome),
            source = outcome.source,
            score = outcome.score,
            message = outcome.message,
            finishedDate = LocalDateTime.now(),
          ),
        )
        publishChanged(item.seriesId)
      }
    } catch (e: Exception) {
      logger.warn(e) { "Failed to scrape ${item.seriesTitle}" }
      queueRepository.update(
        item.copy(
          status = ScrapeQueueItem.Status.FAILED,
          progress = 100,
          attempt = item.attempt + 1,
          lastError = e.message?.take(300) ?: e.javaClass.simpleName,
          finishedDate = LocalDateTime.now(),
        ),
      )
      publishChanged(item.seriesId)
    }
  }

  private fun scopeCount(item: ScrapeQueueItem): Int =
    if (item.bookIds.isNotEmpty()) item.bookIds.size else bookCount(item.seriesId)

  private fun bookCount(seriesId: String): Int =
    runCatching { seriesRepository.findByIdOrNull(seriesId)?.bookCount ?: 0 }.getOrDefault(0)

  private fun bookCountMap(seriesIds: List<String>): Map<String, Int> {
    val ids = seriesIds.distinct().filter { it.isNotBlank() }
    if (ids.isEmpty()) return emptyMap()
    return runCatching { seriesRepository.findAllByIds(ids).associate { it.id to it.bookCount } }.getOrDefault(emptyMap())
  }

  private fun needsFill(seriesId: String): Boolean {
    val metadata = seriesMetadataRepository.findByIdOrNull(seriesId) ?: return true
    return metadata.publisher.isBlank() || metadata.genres.isEmpty() || metadata.summary.isBlank()
  }

  private fun ScrapeQueueItem.toDto(bookCount: Int = 0): ScrapeQueueDto {
    val result =
      result?.takeIf { it.isNotBlank() }?.let {
        runCatching { objectMapper.readValue(it, ScrapeQueueResult::class.java) }.getOrNull()
      }
    val conflicts =
      diff?.takeIf { it.isNotBlank() }?.let {
        runCatching { objectMapper.readValue(it, ScrapeDiff::class.java).conflicts }.getOrNull()
      } ?: 0
    return ScrapeQueueDto(
      id = id,
      seriesId = seriesId,
      seriesTitle = seriesTitle,
      status = status.name,
      origin = origin.name,
      libraryId = libraryId,
      matchId = matchId,
      score = score,
      source = source,
      message = message,
      attempt = attempt,
      progress = progress,
      result = result,
      conflictCount = conflicts,
      bookCount = bookCount,
      createdDate = createdDate.toString(),
      lastModifiedDate = lastModifiedDate.toString(),
      finishedDate = finishedDate?.toString(),
    )
  }

  private fun publishChanged(seriesId: String?) {
    eventPublisher.publishEvent(DomainEvent.ScrapeQueueChanged(seriesId))
  }

  private companion object {
    const val MAX_PAGE_SIZE = 200
    const val MAX_ENQUEUE = 5000
  }
}
