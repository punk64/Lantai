package org.lantai.infrastructure.jooq.main

import org.jooq.impl.DSL
import org.lantai.domain.model.ScrapeInboxEntry
import org.lantai.domain.persistence.ScrapeInboxRepository
import org.lantai.infrastructure.jooq.SplitDslDaoBase
import org.lantai.jooq.main.Tables
import org.lantai.jooq.main.tables.records.ScrapeInboxRecord
import org.lantai.language.toCurrentTimeZone
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.time.ZoneId

@Component
class ScrapeInboxDao(
  dslRW: DSLContext,
  @Qualifier("dslContextRO") dslRO: DSLContext,
) : SplitDslDaoBase(dslRW, dslRO),
  ScrapeInboxRepository {
  private val t = Tables.SCRAPE_INBOX

  override fun insert(entry: ScrapeInboxEntry) {
    val now = LocalDateTime.now(ZoneId.of("Z"))
    dslRW
      .insertInto(t)
      .set(t.ID, entry.id)
      .set(t.QUEUE_ID, entry.queueId)
      .set(t.SERIES_ID, entry.seriesId)
      .set(t.TOKEN_ID, entry.tokenId)
      .set(t.SOURCE, entry.source)
      .set(t.PAYLOAD, entry.payload)
      .set(t.STATUS, entry.status.name)
      .set(t.MESSAGE, entry.message)
      .set(t.CREATED_DATE, now)
      .set(t.LAST_MODIFIED_DATE, now)
      .execute()
  }

  override fun update(entry: ScrapeInboxEntry) {
    dslRW
      .update(t)
      .set(t.STATUS, entry.status.name)
      .set(t.SOURCE, entry.source)
      .set(t.MESSAGE, entry.message)
      .set(t.LAST_MODIFIED_DATE, LocalDateTime.now(ZoneId.of("Z")))
      .where(t.ID.eq(entry.id))
      .execute()
  }

  override fun findByIdOrNull(id: String): ScrapeInboxEntry? =
    dslRO
      .selectFrom(t)
      .where(t.ID.eq(id))
      .fetchOne()
      ?.toDomain()

  override fun findLatestByQueueId(queueId: String): ScrapeInboxEntry? =
    dslRO
      .selectFrom(t)
      .where(t.QUEUE_ID.eq(queueId))
      .orderBy(t.CREATED_DATE.desc())
      .limit(1)
      .fetchOne()
      ?.toDomain()

  override fun findByQueueId(queueId: String): List<ScrapeInboxEntry> =
    dslRO
      .selectFrom(t)
      .where(t.QUEUE_ID.eq(queueId))
      .orderBy(t.CREATED_DATE.desc())
      .fetch()
      .map { it.toDomain() }

  override fun countByStatus(status: ScrapeInboxEntry.Status): Long =
    dslRO
      .select(DSL.count())
      .from(t)
      .where(t.STATUS.eq(status.name))
      .fetchOneInto(Long::class.java)
      ?: 0L

  override fun deleteByQueueId(queueId: String) {
    dslRW
      .deleteFrom(t)
      .where(t.QUEUE_ID.eq(queueId))
      .execute()
  }

  private fun ScrapeInboxRecord.toDomain() =
    ScrapeInboxEntry(
      queueId = queueId,
      seriesId = seriesId,
      payload = payload,
      status = ScrapeInboxEntry.Status.valueOf(status),
      tokenId = tokenId,
      source = source,
      message = message,
      id = id,
      createdDate = createdDate.toCurrentTimeZone(),
      lastModifiedDate = lastModifiedDate.toCurrentTimeZone(),
    )
}
