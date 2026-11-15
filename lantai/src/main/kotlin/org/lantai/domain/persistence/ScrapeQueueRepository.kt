package org.lantai.domain.persistence

import org.lantai.domain.model.ScrapeQueueItem

data class ScrapeQueueBrief(
  val status: ScrapeQueueItem.Status,
  val seriesId: String,
  val bookIds: List<String>,
)

interface ScrapeQueueRepository {
  fun insert(item: ScrapeQueueItem)

  fun update(item: ScrapeQueueItem)

  fun findByIdOrNull(id: String): ScrapeQueueItem?

  fun findBySeriesId(seriesId: String): ScrapeQueueItem?

  fun findAllBrief(): List<ScrapeQueueBrief>

  fun findPage(
    status: ScrapeQueueItem.Status?,
    offset: Int,
    limit: Int,
  ): List<ScrapeQueueItem>

  fun countByStatus(status: ScrapeQueueItem.Status?): Long

  fun countGrouped(): Map<String, Long>

  fun findQueued(limit: Int): List<ScrapeQueueItem>

  fun findRunning(): List<ScrapeQueueItem>

  fun deleteById(id: String)

  fun deleteBySeriesId(seriesId: String)

  fun deleteByLibraryId(libraryId: String)

  fun deleteOrphans(): Int

  fun deleteByStatuses(statuses: List<ScrapeQueueItem.Status>)

  fun deleteAll()
}
