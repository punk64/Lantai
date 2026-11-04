package org.lantai.infrastructure.jooq.main

import org.jooq.impl.DSL
import org.lantai.domain.model.ScrapeQueueItem
import org.lantai.domain.persistence.ScrapeQueueBrief
import org.lantai.domain.persistence.ScrapeQueueRepository
import org.lantai.infrastructure.jooq.SplitDslDaoBase
import org.lantai.jooq.main.Tables
import org.lantai.jooq.main.tables.records.ScrapeQueueRecord
import org.lantai.language.toCurrentTimeZone
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.time.ZoneId

@Component
class ScrapeQueueDao(
  dslRW: DSLContext,
  @Qualifier("dslContextRO") dslRO: DSLContext,
) : SplitDslDaoBase(dslRW, dslRO),
  ScrapeQueueRepository {
  private val q = Tables.SCRAPE_QUEUE

  override fun insert(item: ScrapeQueueItem) {
    val now = LocalDateTime.now(ZoneId.of("Z"))
    dslRW
      .insertInto(q)
      .set(q.ID, item.id)
      .set(q.SERIES_ID, item.seriesId)
      .set(q.SERIES_TITLE, item.seriesTitle)
      .set(q.STATUS, item.status.name)
      .set(q.ORIGIN, item.origin.name)
      .set(q.LIBRARY_ID, item.libraryId)
      .set(q.BOOK_IDS, item.bookIds.takeIf { it.isNotEmpty() }?.joinToString(","))
      .set(q.MATCH_ID, item.matchId)
      .set(q.SCORE, item.score?.toFloat())
      .set(q.SOURCE, item.source)
      .set(q.MESSAGE, item.message)
      .set(q.ATTEMPT, item.attempt)
      .set(q.PROGRESS, item.progress)
      .set(q.DIFF, item.diff)
      .set(q.RESULT, item.result)
      .set(q.LAST_ERROR, item.lastError)
      .set(q.FINISHED_DATE, item.finishedDate?.atZone(ZoneId.systemDefault())?.withZoneSameInstant(ZoneId.of("Z"))?.toLocalDateTime())
      .set(q.CREATED_DATE, now)
      .set(q.LAST_MODIFIED_DATE, now)
      .execute()
  }

  override fun update(item: ScrapeQueueItem) {
    dslRW
      .update(q)
      .set(q.SERIES_TITLE, item.seriesTitle)
      .set(q.STATUS, item.status.name)
      .set(q.ORIGIN, item.origin.name)
      .set(q.LIBRARY_ID, item.libraryId)
      .set(q.BOOK_IDS, item.bookIds.takeIf { it.isNotEmpty() }?.joinToString(","))
      .set(q.MATCH_ID, item.matchId)
      .set(q.SCORE, item.score?.toFloat())
      .set(q.SOURCE, item.source)
      .set(q.MESSAGE, item.message)
      .set(q.ATTEMPT, item.attempt)
      .set(q.PROGRESS, item.progress)
      .set(q.DIFF, item.diff)
      .set(q.RESULT, item.result)
      .set(q.LAST_ERROR, item.lastError)
      .set(q.FINISHED_DATE, item.finishedDate?.atZone(ZoneId.systemDefault())?.withZoneSameInstant(ZoneId.of("Z"))?.toLocalDateTime())
      .set(q.LAST_MODIFIED_DATE, LocalDateTime.now(ZoneId.of("Z")))
      .where(q.ID.eq(item.id))
      .execute()
  }

  override fun findByIdOrNull(id: String): ScrapeQueueItem? =
    dslRO
      .selectFrom(q)
      .where(q.ID.eq(id))
      .fetchOne()
      ?.toDomain()

  override fun findBySeriesId(seriesId: String): ScrapeQueueItem? =
    dslRO
      .selectFrom(q)
      .where(q.SERIES_ID.eq(seriesId))
      .fetchOne()
      ?.toDomain()

  override fun findAllBrief(): List<ScrapeQueueBrief> =
    dslRO
      .select(q.STATUS, q.SERIES_ID, q.BOOK_IDS)
      .from(q)
      .fetch()
      .map {
        ScrapeQueueBrief(
          status = ScrapeQueueItem.Status.valueOf(it.value1()),
          seriesId = it.value2(),
          bookIds = it.value3()?.split(",")?.filter { id -> id.isNotBlank() }.orEmpty(),
        )
      }

  override fun findPage(
    status: ScrapeQueueItem.Status?,
    offset: Int,
    limit: Int,
  ): List<ScrapeQueueItem> {
    val base = dslRO.selectFrom(q)
    val filtered = if (status == null) base else base.where(q.STATUS.eq(status.name))
    return filtered
      .orderBy(q.CREATED_DATE.desc())
      .limit(limit.coerceIn(1, MAX_PAGE_SIZE))
      .offset(offset.coerceAtLeast(0))
      .fetch()
      .map { it.toDomain() }
  }

  override fun countByStatus(status: ScrapeQueueItem.Status?): Long {
    val base = dslRO.select(DSL.count()).from(q)
    val filtered = if (status == null) base else base.where(q.STATUS.eq(status.name))
    return filtered.fetchOneInto(Long::class.java) ?: 0L
  }

  override fun countGrouped(): Map<String, Long> =
    dslRO
      .select(q.STATUS, DSL.count())
      .from(q)
      .groupBy(q.STATUS)
      .fetch()
      .associate { it.value1() to (it.value2()?.toLong() ?: 0L) }

  override fun findQueued(limit: Int): List<ScrapeQueueItem> =
    dslRO
      .selectFrom(q)
      .where(q.STATUS.eq(ScrapeQueueItem.Status.QUEUED.name))
      .orderBy(q.CREATED_DATE.asc())
      .limit(limit.coerceIn(1, MAX_PAGE_SIZE))
      .fetch()
      .map { it.toDomain() }

  override fun findRunning(): List<ScrapeQueueItem> =
    dslRO
      .selectFrom(q)
      .where(q.STATUS.eq(ScrapeQueueItem.Status.RUNNING.name))
      .fetch()
      .map { it.toDomain() }

  override fun deleteById(id: String) {
    dslRW
      .deleteFrom(q)
      .where(q.ID.eq(id))
      .execute()
  }

  override fun deleteBySeriesId(seriesId: String) {
    dslRW
      .deleteFrom(q)
      .where(q.SERIES_ID.eq(seriesId))
      .execute()
  }

  override fun deleteByLibraryId(libraryId: String) {
    dslRW
      .deleteFrom(q)
      .where(q.LIBRARY_ID.eq(libraryId))
      .execute()
  }

  override fun deleteOrphans(): Int {
    val s = Tables.SERIES
    return dslRW
      .deleteFrom(q)
      .whereNotExists(dslRW.selectOne().from(s).where(s.ID.eq(q.SERIES_ID)))
      .execute()
  }

  override fun deleteByStatuses(statuses: List<ScrapeQueueItem.Status>) {
    if (statuses.isEmpty()) return
    dslRW
      .deleteFrom(q)
      .where(q.STATUS.`in`(statuses.map { it.name }))
      .execute()
  }

  override fun deleteAll() {
    dslRW
      .deleteFrom(q)
      .execute()
  }

  private fun ScrapeQueueRecord.toDomain() =
    ScrapeQueueItem(
      seriesId = seriesId,
      seriesTitle = seriesTitle,
      status = ScrapeQueueItem.Status.valueOf(status),
      origin = ScrapeQueueItem.Origin.valueOf(origin ?: ScrapeQueueItem.Origin.SERIES.name),
      libraryId = libraryId,
      bookIds = bookIds?.split(",")?.filter { it.isNotBlank() }.orEmpty(),
      matchId = matchId,
      score = score?.toDouble(),
      source = source,
      message = message,
      attempt = attempt ?: 0,
      progress = progress ?: 0,
      diff = diff,
      result = result,
      lastError = lastError,
      finishedDate = finishedDate?.toCurrentTimeZone(),
      id = id,
      createdDate = createdDate.toCurrentTimeZone(),
      lastModifiedDate = lastModifiedDate.toCurrentTimeZone(),
    )

  private companion object {
    const val MAX_PAGE_SIZE = 200
  }
}
