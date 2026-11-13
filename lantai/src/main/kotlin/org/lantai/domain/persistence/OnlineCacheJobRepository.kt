package org.lantai.domain.persistence

import org.lantai.domain.model.OnlineCacheItem
import org.lantai.domain.model.OnlineCacheJob

interface OnlineCacheJobRepository {
  fun insertJob(job: OnlineCacheJob)

  fun updateJob(job: OnlineCacheJob)

  fun findJobByIdOrNull(jobId: String): OnlineCacheJob?

  fun findJobsBySeriesId(seriesId: String): Collection<OnlineCacheJob>

  fun findRunnableJob(): OnlineCacheJob?

  fun findJobsByStatus(status: OnlineCacheJob.Status): Collection<OnlineCacheJob>

  fun insertItems(items: Collection<OnlineCacheItem>)

  fun insertItem(item: OnlineCacheItem)

  fun updateItem(item: OnlineCacheItem)

  fun findItemByIdOrNull(itemId: String): OnlineCacheItem?

  fun findItemsByJobId(jobId: String): Collection<OnlineCacheItem>

  fun findNextPendingItem(jobId: String): OnlineCacheItem?

  fun countItems(jobId: String): Long

  fun pageItems(jobId: String, offset: Long, limit: Int): Collection<OnlineCacheItem>

  fun resetFailedItems(jobId: String): Int

  fun resetRunningItems(jobId: String): Int

  fun deleteByJobId(jobId: String)

  fun deleteBySeriesId(seriesId: String)
}
