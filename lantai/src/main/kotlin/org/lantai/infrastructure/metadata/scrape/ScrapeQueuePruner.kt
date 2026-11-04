package org.lantai.infrastructure.metadata.scrape

import io.github.oshai.kotlinlogging.KotlinLogging
import org.lantai.domain.model.DomainEvent
import org.lantai.domain.persistence.ScrapeInboxRepository
import org.lantai.domain.persistence.ScrapeQueueRepository
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class ScrapeQueuePruner(
  private val queueRepository: ScrapeQueueRepository,
  private val inboxRepository: ScrapeInboxRepository,
) {
  @EventListener
  fun onSeriesDeleted(event: DomainEvent.SeriesDeleted) {
    runCatching {
      queueRepository.deleteBySeriesId(event.series.id)
      inboxRepository.deleteBySeriesId(event.series.id)
    }.onFailure { logger.warn(it) { "Failed to prune scrape rows of series ${event.series.id}" } }
  }

  @EventListener
  fun onLibraryDeleted(event: DomainEvent.LibraryDeleted) {
    runCatching { queueRepository.deleteByLibraryId(event.library.id) }
      .onFailure { logger.warn(it) { "Failed to prune scrape rows of library ${event.library.id}" } }
  }

  @EventListener(ApplicationReadyEvent::class)
  fun sweepOrphans() {
    val queue = runCatching { queueRepository.deleteOrphans() }.getOrElse { logger.warn(it) { "Scrape queue orphan sweep failed" }; 0 }
    val inbox = runCatching { inboxRepository.deleteOrphans() }.getOrElse { logger.warn(it) { "Scrape inbox orphan sweep failed" }; 0 }
    if (queue > 0 || inbox > 0) logger.info { "Scrape orphan rows removed: queue=$queue inbox=$inbox" }
  }
}
