package org.lantai.domain.persistence

import org.lantai.domain.model.ScrapeInboxEntry

interface ScrapeInboxRepository {
  fun insert(entry: ScrapeInboxEntry)

  fun update(entry: ScrapeInboxEntry)

  fun findByIdOrNull(id: String): ScrapeInboxEntry?

  fun findLatestByQueueId(queueId: String): ScrapeInboxEntry?

  fun findByQueueId(queueId: String): List<ScrapeInboxEntry>

  fun countByStatus(status: ScrapeInboxEntry.Status): Long

  fun deleteByQueueId(queueId: String)
}
