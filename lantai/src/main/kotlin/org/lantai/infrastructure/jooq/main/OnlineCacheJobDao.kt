package org.lantai.infrastructure.jooq.main

import org.lantai.domain.model.OnlineCacheItem
import org.lantai.domain.model.OnlineCacheJob
import org.lantai.domain.persistence.OnlineCacheJobRepository
import org.lantai.infrastructure.jooq.SplitDslDaoBase
import org.lantai.jooq.main.Tables
import org.lantai.jooq.main.tables.records.OnlineCacheItemRecord
import org.lantai.jooq.main.tables.records.OnlineCacheJobRecord
import org.lantai.language.toCurrentTimeZone
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.ZoneId

@Component
class OnlineCacheJobDao(
  dslRW: DSLContext,
  @Qualifier("dslContextRO") dslRO: DSLContext,
) : SplitDslDaoBase(dslRW, dslRO),
  OnlineCacheJobRepository {
  private val j = Tables.ONLINE_CACHE_JOB
  private val i = Tables.ONLINE_CACHE_ITEM

  @Transactional
  override fun insertJob(job: OnlineCacheJob) {
    val now = LocalDateTime.now(ZoneId.of("Z"))
    dslRW
      .insertInto(
        j,
        j.ID,
        j.SERIES_ID,
        j.STATUS,
        j.TOTAL,
        j.DONE,
        j.FAILED,
        j.CREATED_DATE,
        j.LAST_MODIFIED_DATE,
      ).values(job.id, job.seriesId, job.status.name, job.total, job.done, job.failed, now, now)
      .execute()
  }

  @Transactional
  override fun updateJob(job: OnlineCacheJob) {
    val now = LocalDateTime.now(ZoneId.of("Z"))
    dslRW
      .update(j)
      .set(j.STATUS, job.status.name)
      .set(j.TOTAL, job.total)
      .set(j.DONE, job.done)
      .set(j.FAILED, job.failed)
      .set(j.LAST_MODIFIED_DATE, now)
      .where(j.ID.eq(job.id))
      .execute()
  }

  override fun findJobByIdOrNull(jobId: String): OnlineCacheJob? =
    dslRO
      .selectFrom(j)
      .where(j.ID.eq(jobId))
      .fetchOne()
      ?.toDomain()

  override fun findJobsBySeriesId(seriesId: String): Collection<OnlineCacheJob> =
    dslRO
      .selectFrom(j)
      .where(j.SERIES_ID.eq(seriesId))
      .orderBy(j.CREATED_DATE.desc())
      .limit(20)
      .fetch()
      .map { it.toDomain() }

  override fun findRunnableJob(): OnlineCacheJob? =
    dslRO
      .selectFrom(j)
      .where(j.STATUS.`in`(OnlineCacheJob.Status.PENDING.name, OnlineCacheJob.Status.RUNNING.name))
      .orderBy(j.CREATED_DATE.asc())
      .limit(1)
      .fetchOne()
      ?.toDomain()

  override fun findJobsByStatus(status: OnlineCacheJob.Status): Collection<OnlineCacheJob> =
    dslRO
      .selectFrom(j)
      .where(j.STATUS.eq(status.name))
      .fetch()
      .map { it.toDomain() }

  @Transactional
  override fun insertItems(items: Collection<OnlineCacheItem>) {
    if (items.isEmpty()) return
    val now = LocalDateTime.now(ZoneId.of("Z"))
    items.forEach { item ->
      dslRW
        .insertInto(i)
        .set(i.ID, item.id)
        .set(i.JOB_ID, item.jobId)
        .set(i.BOOK_ID, item.bookId)
        .set(i.CHAP_INDEX, item.chapIndex)
        .set(i.TITLE, item.title)
        .set(i.STATUS, item.status.name)
        .set(i.ERROR, item.error)
        .set(i.PAGE_COUNT, item.pageCount)
        .set(i.SIZE_BYTES, item.sizeBytes)
        .set(i.CREATED_DATE, now)
        .set(i.LAST_MODIFIED_DATE, now)
        .execute()
    }
  }

  @Transactional
  override fun insertItem(item: OnlineCacheItem) {
    insertItems(listOf(item))
  }

  @Transactional
  override fun updateItem(item: OnlineCacheItem) {
    val now = LocalDateTime.now(ZoneId.of("Z"))
    dslRW
      .update(i)
      .set(i.STATUS, item.status.name)
      .set(i.ERROR, item.error)
      .set(i.PAGE_COUNT, item.pageCount)
      .set(i.SIZE_BYTES, item.sizeBytes)
      .set(i.LAST_MODIFIED_DATE, now)
      .where(i.ID.eq(item.id))
      .execute()
  }

  override fun findItemByIdOrNull(itemId: String): OnlineCacheItem? =
    dslRO
      .selectFrom(i)
      .where(i.ID.eq(itemId))
      .fetchOne()
      ?.toDomain()

  override fun findItemsByJobId(jobId: String): Collection<OnlineCacheItem> =
    dslRO
      .selectFrom(i)
      .where(i.JOB_ID.eq(jobId))
      .orderBy(i.CHAP_INDEX.asc())
      .fetch()
      .map { it.toDomain() }

  override fun findNextPendingItem(jobId: String): OnlineCacheItem? =
    dslRO
      .selectFrom(i)
      .where(i.JOB_ID.eq(jobId))
      .and(i.STATUS.eq(OnlineCacheItem.Status.PENDING.name))
      .orderBy(i.CHAP_INDEX.asc())
      .limit(1)
      .fetchOne()
      ?.toDomain()

  override fun countItems(jobId: String): Long = dslRO.fetchCount(i, i.JOB_ID.eq(jobId)).toLong()

  override fun pageItems(
    jobId: String,
    offset: Long,
    limit: Int,
  ): Collection<OnlineCacheItem> =
    dslRO
      .selectFrom(i)
      .where(i.JOB_ID.eq(jobId))
      .orderBy(i.CHAP_INDEX.asc())
      .limit(limit)
      .offset(offset)
      .fetch()
      .map { it.toDomain() }

  @Transactional
  override fun resetFailedItems(jobId: String): Int {
    val now = LocalDateTime.now(ZoneId.of("Z"))
    return dslRW
      .update(i)
      .set(i.STATUS, OnlineCacheItem.Status.PENDING.name)
      .set(i.ERROR, "")
      .set(i.LAST_MODIFIED_DATE, now)
      .where(i.JOB_ID.eq(jobId))
      .and(i.STATUS.eq(OnlineCacheItem.Status.FAILED.name))
      .execute()
  }

  @Transactional
  override fun resetRunningItems(jobId: String): Int {
    val now = LocalDateTime.now(ZoneId.of("Z"))
    return dslRW
      .update(i)
      .set(i.STATUS, OnlineCacheItem.Status.PENDING.name)
      .set(i.LAST_MODIFIED_DATE, now)
      .where(i.JOB_ID.eq(jobId))
      .and(i.STATUS.eq(OnlineCacheItem.Status.RUNNING.name))
      .execute()
  }

  @Transactional
  override fun deleteByJobId(jobId: String) {
    dslRW.deleteFrom(i).where(i.JOB_ID.eq(jobId)).execute()
    dslRW.deleteFrom(j).where(j.ID.eq(jobId)).execute()
  }

  @Transactional
  override fun deleteBySeriesId(seriesId: String) {
    val jobIds = dslRO.select(j.ID).from(j).where(j.SERIES_ID.eq(seriesId)).fetch(j.ID)
    if (jobIds.isNotEmpty()) dslRW.deleteFrom(i).where(i.JOB_ID.`in`(jobIds)).execute()
    dslRW.deleteFrom(j).where(j.SERIES_ID.eq(seriesId)).execute()
  }

  private fun OnlineCacheJobRecord.toDomain() =
    OnlineCacheJob(
      seriesId = seriesId,
      status = OnlineCacheJob.Status.valueOf(status),
      total = total,
      done = done,
      failed = failed,
      id = id,
      createdDate = createdDate.toCurrentTimeZone(),
      lastModifiedDate = lastModifiedDate.toCurrentTimeZone(),
    )

  private fun OnlineCacheItemRecord.toDomain() =
    OnlineCacheItem(
      jobId = jobId,
      bookId = bookId,
      chapIndex = chapIndex,
      title = title,
      status = OnlineCacheItem.Status.valueOf(status),
      error = error,
      pageCount = pageCount,
      sizeBytes = sizeBytes,
      id = id,
      createdDate = createdDate.toCurrentTimeZone(),
      lastModifiedDate = lastModifiedDate.toCurrentTimeZone(),
    )
}
