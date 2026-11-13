package org.lantai.infrastructure.metadata.scrape

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.lantai.domain.model.DomainEvent
import org.lantai.domain.model.ScrapeInboxEntry
import org.lantai.domain.model.ScrapeQueueItem
import org.lantai.domain.persistence.BookMetadataAggregationRepository
import org.lantai.domain.persistence.ScrapeInboxRepository
import org.lantai.domain.persistence.ScrapeQueueRepository
import org.lantai.domain.persistence.SeriesMetadataRepository
import org.lantai.domain.persistence.SeriesRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.time.LocalDateTime

private val logger = KotlinLogging.logger {}

data class ScrapeAgentTask(
  val id: String,
  val seriesId: String,
  val seriesTitle: String,
  val status: String,
  val attempt: Int,
  val createdDate: String,
  val lastError: String?,
  val bookIds: List<String> = emptyList(),
)

data class ScrapeAgentDetail(
  val id: String,
  val seriesId: String,
  val seriesTitle: String,
  val status: String,
  val attempt: Int,
  val libraryId: String?,
  val summary: String,
  val publisher: String,
  val authors: List<String>,
  val genres: List<String>,
  val hasCover: Boolean,
  val books: List<ScrapeBookDto> = emptyList(),
  val external: JsonNode?,
)

data class ScrapeAgentStats(
  val total: Int,
  val queued: Int,
  val review: Int,
  val failed: Int,
  val inboxReceived: Int,
)

data class ScrapeAgentSubmitResult(
  val queued: Boolean,
  val status: String,
  val appliedFields: List<String>,
  val message: String?,
  val keptFields: List<String> = emptyList(),
  val conflicts: List<String> = emptyList(),
  val conflictPolicy: String = ScrapeSettingsProvider.CONFLICT_POLICY_REVIEW,
)

@Service
class ScrapeAgentService(
  private val queueRepository: ScrapeQueueRepository,
  private val inboxRepository: ScrapeInboxRepository,
  private val seriesRepository: SeriesRepository,
  private val seriesMetadataRepository: SeriesMetadataRepository,
  private val engine: ScrapeEngine,
  private val applier: ScrapeApplier,
  private val bookCatalog: ScrapeBookCatalog,
  private val settings: ScrapeSettingsProvider,
  private val thumbnailsSeriesRepository: org.lantai.domain.persistence.ThumbnailSeriesRepository,
  private val aggregationRepository: BookMetadataAggregationRepository,
  private val eventPublisher: ApplicationEventPublisher,
  private val objectMapper: ObjectMapper,
) {
  fun page(
    offset: Int,
    limit: Int,
  ): Map<String, Any> {
    val safeLimit = limit.coerceIn(1, 200)
    val items = queueRepository.findPage(null, offset.coerceAtLeast(0), safeLimit)
    val grouped = queueRepository.countGrouped()
    return mapOf(
      "total" to grouped.values.sum().toInt(),
      "offset" to offset.coerceAtLeast(0),
      "limit" to safeLimit,
      "counts" to
        mapOf(
          "queued" to (grouped[ScrapeQueueItem.Status.QUEUED.name] ?: 0),
          "running" to (grouped[ScrapeQueueItem.Status.RUNNING.name] ?: 0),
          "review" to (grouped[ScrapeQueueItem.Status.REVIEW.name] ?: 0),
          "failed" to (grouped[ScrapeQueueItem.Status.FAILED.name] ?: 0),
        ),
      "items" to items.map { ScrapeAgentTask(it.id, it.seriesId, it.seriesTitle, it.status.name, it.attempt, it.createdDate.toString(), it.lastError, it.bookIds) },
    )
  }

  fun detail(id: String): ScrapeAgentDetail {
    val item = queueRepository.findByIdOrNull(id) ?: throw NoSuchElementException("Queue item $id does not exist")
    seriesRepository.findByIdOrNull(item.seriesId) ?: throw NoSuchElementException("Series ${item.seriesId} does not exist")
    val metadata = seriesMetadataRepository.findByIdOrNull(item.seriesId)
    val aggregation = runCatching { aggregationRepository.findByIdOrNull(item.seriesId) }.getOrNull()
    val external =
      inboxRepository.findLatestByQueueId(id)?.payload?.let { runCatching { objectMapper.readTree(it) }.getOrNull() }
    return ScrapeAgentDetail(
      id = item.id,
      seriesId = item.seriesId,
      seriesTitle = item.seriesTitle,
      status = item.status.name,
      attempt = item.attempt,
      libraryId = item.libraryId,
      summary = metadata?.summary.orEmpty(),
      publisher = metadata?.publisher.orEmpty(),
      authors = aggregation?.authors?.map { it.name }.orEmpty(),
      genres = metadata?.genres?.sorted().orEmpty(),
      hasCover = runCatching { thumbnailsSeriesRepository.findSelectedBySeriesIdOrNull(item.seriesId) != null }.getOrDefault(false),
      books =
        runCatching {
          val all = bookCatalog.list(item.seriesId)
          if (item.bookIds.isEmpty()) all else all.filter { it.bookId in item.bookIds }
        }.getOrDefault(emptyList()),
      external = external,
    )
  }

  fun submit(
    id: String,
    rawPayload: String,
    tokenId: String?,
    sourceName: String,
  ): ScrapeAgentSubmitResult {
    val item = queueRepository.findByIdOrNull(id)
    val seriesId =
      item?.seriesId
        ?: id.takeIf { candidate -> seriesRepository.findByIdOrNull(candidate) != null }
        ?: throw NoSuchElementException("Neither a queue entry nor a series matches $id")
    val overwrite = item == null
    val pending = item ?: queueRepository.findBySeriesId(seriesId)
    val entry =
      ScrapeInboxEntry(
        queueId = pending?.id ?: seriesId,
        seriesId = seriesId,
        payload = rawPayload,
        tokenId = tokenId,
        source = sourceName,
      )
    inboxRepository.insert(entry)

    val values =
      try {
        ScrapePayloadParser.parse(objectMapper, rawPayload)
      } catch (e: Exception) {
        inboxRepository.update(entry.copy(status = ScrapeInboxEntry.Status.REJECTED, message = e.message?.take(300)))
        throw ScrapeException("刮削结果无法解析：${e.message}")
      }

    return try {
      val scope = pending?.bookIds?.toSet().orEmpty()
      val trust = overwrite || settings.trustResult
      val policy = settings.effectivePolicy(trust)
      val bookChoices = runCatching { ScrapePayloadParser.parseBooks(objectMapper, rawPayload) }.getOrDefault(emptyList())
      val bookApplied =
        if (bookChoices.isEmpty()) {
          emptyList()
        } else {
          runCatching {
            applier
              .applyBookValues(
                seriesId = seriesId,
                choices = bookChoices,
                source = sourceName.ifBlank { "agent" },
                score = 1.0,
                scope = scope,
                onlyFill = policy != ScrapeSettingsProvider.CONFLICT_POLICY_OVERWRITE,
              ).appliedFields
          }.getOrDefault(emptyList())
        }
      val outcome =
        runCatching { engine.externalAnalyze(seriesId, values, sourceName.ifBlank { "agent" }, 1.0, trust, scope) }
          .getOrElse { error ->
            if (bookApplied.isEmpty()) throw error
            ScrapeOutcome(
              diff = ScrapeDiff(),
              applied = ScrapeApplySummary(bookApplied, emptyList(), false, null),
              source = sourceName,
              score = 1.0,
              message = error.message,
              status = "DONE",
            )
          }
      inboxRepository.update(entry.copy(status = ScrapeInboxEntry.Status.PARSED, message = outcome.message?.take(300)))
      if (pending != null) {
        if (outcome.status == "DONE") {
          queueRepository.deleteById(pending.id)
        } else {
          queueRepository.update(
            pending.copy(
              status = ScrapeQueueItem.Status.REVIEW,
              progress = 100,
              diff = engine.serializedDiff(outcome.diff.unresolved()),
              source = outcome.source,
              score = outcome.score,
              message = outcome.message,
              finishedDate = LocalDateTime.now(),
            ),
          )
        }
      }
      eventPublisher.publishEvent(DomainEvent.ScrapeQueueChanged(seriesId))
      ScrapeAgentSubmitResult(
        queued = false,
        status = outcome.status,
        appliedFields = (bookApplied + outcome.applied?.appliedFields.orEmpty()).distinct(),
        message = outcome.message,
        keptFields = outcome.kept,
        conflicts = outcome.diff.pendingConflicts().map { it.field }.distinct(),
        conflictPolicy = policy,
      )
    } catch (e: Exception) {
      logger.warn(e) { "Failed to apply external scrape result for $seriesId" }
      inboxRepository.update(entry.copy(status = ScrapeInboxEntry.Status.REJECTED, message = e.message?.take(300)))
      throw e
    }
  }

  fun stats(): ScrapeAgentStats {
    val grouped = queueRepository.countGrouped()
    return ScrapeAgentStats(
      total = grouped.values.sum().toInt(),
      queued = grouped[ScrapeQueueItem.Status.QUEUED.name]?.toInt() ?: 0,
      review = grouped[ScrapeQueueItem.Status.REVIEW.name]?.toInt() ?: 0,
      failed = grouped[ScrapeQueueItem.Status.FAILED.name]?.toInt() ?: 0,
      inboxReceived = inboxRepository.countByStatus(ScrapeInboxEntry.Status.RECEIVED).toInt(),
    )
  }

}
