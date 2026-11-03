package org.lantai.infrastructure.metadata.scrape

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.lantai.domain.model.DomainEvent
import org.lantai.domain.model.ScrapeInboxEntry
import org.lantai.domain.model.ScrapeQueueItem
import org.lantai.domain.persistence.ScrapeInboxRepository
import org.lantai.domain.persistence.ScrapeQueueRepository
import org.lantai.domain.persistence.SeriesMetadataRepository
import org.lantai.domain.persistence.SeriesRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.time.LocalDate
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
  val embeddedAuthors: List<String>,
  val embeddedPublisher: String,
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
)

@Service
class ScrapeAgentService(
  private val queueRepository: ScrapeQueueRepository,
  private val inboxRepository: ScrapeInboxRepository,
  private val seriesRepository: SeriesRepository,
  private val seriesMetadataRepository: SeriesMetadataRepository,
  private val embeddedReader: ScrapeEmbeddedReader,
  private val engine: ScrapeEngine,
  private val settings: ScrapeSettingsProvider,
  private val thumbnailsSeriesRepository: org.lantai.domain.persistence.ThumbnailSeriesRepository,
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
      "items" to items.map { ScrapeAgentTask(it.id, it.seriesId, it.seriesTitle, it.status.name, it.attempt, it.createdDate.toString(), it.lastError) },
    )
  }

  fun detail(id: String): ScrapeAgentDetail {
    val item = queueRepository.findByIdOrNull(id) ?: throw NoSuchElementException("Queue item $id does not exist")
    val series = seriesRepository.findByIdOrNull(item.seriesId) ?: throw NoSuchElementException("Series ${item.seriesId} does not exist")
    val metadata = seriesMetadataRepository.findByIdOrNull(item.seriesId)
    val embedded = runCatching { embeddedReader.read(series) }.getOrNull() ?: EmbeddedRead()
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
      authors = embedded.firstBookValues().authors,
      genres = metadata?.genres?.sorted().orEmpty(),
      hasCover = runCatching { thumbnailsSeriesRepository.findSelectedBySeriesIdOrNull(item.seriesId) != null }.getOrDefault(false),
      embeddedAuthors = embedded.firstBookValues().authors,
      embeddedPublisher = embedded.series.publisher,
      external = external,
    )
  }

  fun submit(
    id: String,
    rawPayload: String,
    tokenId: String?,
    sourceName: String,
  ): ScrapeAgentSubmitResult {
    val item = queueRepository.findByIdOrNull(id) ?: throw NoSuchElementException("Queue item $id does not exist")
    val entry =
      ScrapeInboxEntry(
        queueId = item.id,
        seriesId = item.seriesId,
        payload = rawPayload,
        tokenId = tokenId,
        source = sourceName,
      )
    inboxRepository.insert(entry)

    val values =
      try {
        parse(rawPayload)
      } catch (e: Exception) {
        inboxRepository.update(entry.copy(status = ScrapeInboxEntry.Status.REJECTED, message = e.message?.take(300)))
        throw ScrapeException("刮削结果无法解析：${e.message}")
      }

    return try {
      val outcome = engine.externalAnalyze(item.seriesId, values, sourceName.ifBlank { "agent" }, 1.0, settings.trustResult)
      inboxRepository.update(entry.copy(status = ScrapeInboxEntry.Status.PARSED, message = outcome.message?.take(300)))
      if (outcome.status == "DONE") {
        queueRepository.deleteById(item.id)
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
      }
      eventPublisher.publishEvent(DomainEvent.ScrapeQueueChanged(item.seriesId))
      ScrapeAgentSubmitResult(
        queued = false,
        status = outcome.status,
        appliedFields = outcome.applied?.appliedFields.orEmpty(),
        message = outcome.message,
      )
    } catch (e: Exception) {
      logger.warn(e) { "Failed to apply external scrape result for ${item.seriesId}" }
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

  private fun parse(raw: String): ScrapeValues {
    val root = objectMapper.readTree(raw)
    val node = if (root.has("metadata") && root.get("metadata").isObject) root.get("metadata") else root
    return ScrapeValues(
      title = text(node, "title"),
      summary = text(node, "summary").ifBlank { text(node, "description") },
      publisher = text(node, "publisher"),
      language = text(node, "language"),
      ageRating = text(node, "ageRating").toIntOrNull(),
      genres = list(node, "genres").ifEmpty { list(node, "categories") },
      authors = list(node, "authors").ifEmpty { list(node, "author") },
      releaseDate = date(text(node, "releaseDate").ifBlank { text(node, "publishDate") }),
      isbn = text(node, "isbn"),
      tags = list(node, "tags"),
      coverUrl = text(node, "coverUrl").ifBlank { text(node, "cover") },
    )
  }

  private fun text(
    node: JsonNode,
    field: String,
  ): String {
    val value = node.get(field) ?: return ""
    return when {
      value.isNull -> ""
      value.isArray -> value.mapNotNull { it.asText().takeIf { text -> text.isNotBlank() } }.joinToString("、")
      else -> value.asText().trim()
    }
  }

  private fun list(
    node: JsonNode,
    field: String,
  ): List<String> {
    val value = node.get(field) ?: return emptyList()
    return when {
      value.isNull -> emptyList()
      value.isArray -> value.mapNotNull { it.asText().trim().takeIf { text -> text.isNotEmpty() } }
      else -> value.asText().split('、', ',', '/').mapNotNull { it.trim().takeIf { text -> text.isNotEmpty() } }
    }
  }

  private fun date(value: String): LocalDate? {
    if (value.isBlank()) return null
    val trimmed = value.trim().take(10)
    return runCatching { LocalDate.parse(trimmed) }.getOrNull()
  }
}
