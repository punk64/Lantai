package org.lantai.infrastructure.jooq.main

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.lantai.infrastructure.jooq.SplitDslDaoBase
import org.lantai.jooq.main.Tables
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.time.LocalDateTime

@Component
class LantaiReadingQueryDao(
  dslRW: DSLContext,
  @Qualifier("dslContextRO") dslRO: DSLContext,
) : SplitDslDaoBase(dslRW, dslRO) {
  private val s = Tables.READING_SESSION

  fun devices(): List<LantaiDeviceRow> =
    dslRO
      .select(
        s.DEVICE_ID,
        DSL.max(s.DEVICE_NAME),
        DSL.max(s.CLIENT),
        DSL.count(),
        DSL.sum(s.DURATION_MS),
        DSL.sum(s.PAGES_READ),
        DSL.countDistinct(s.BOOK_ID),
        DSL.max(s.LAST_HEARTBEAT_AT),
        DSL.min(s.STARTED_AT),
      ).from(s)
      .groupBy(s.DEVICE_ID)
      .orderBy(DSL.max(s.LAST_HEARTBEAT_AT).desc())
      .fetch { record ->
        LantaiDeviceRow(
          deviceId = record.value1().orEmpty(),
          deviceName = record.value2(),
          client = record.value3(),
          sessionCount = record.value4(),
          durationMs = record.value5()?.toLong() ?: 0L,
          pagesRead = record.value6()?.toInt() ?: 0,
          bookCount = record.value7(),
          lastActiveAt = record.value8(),
          firstSeenAt = record.value9(),
        )
      }

  fun sessions(
    deviceId: String?,
    from: LocalDateTime?,
    to: LocalDateTime?,
    limit: Int,
    offset: Int,
  ): List<LantaiSessionRow> {
    var condition = DSL.noCondition()
    if (!deviceId.isNullOrBlank()) condition = condition.and(s.DEVICE_ID.eq(deviceId))
    if (from != null) condition = condition.and(s.STARTED_AT.ge(from))
    if (to != null) condition = condition.and(s.STARTED_AT.le(to))
    return dslRO
      .selectFrom(s)
      .where(condition)
      .orderBy(s.STARTED_AT.desc())
      .limit(limit)
      .offset(offset)
      .fetch { record ->
        LantaiSessionRow(
          id = record.id,
          userId = record.userId,
          bookId = record.bookId,
          seriesId = record.seriesId,
          libraryId = record.libraryId,
          startedAt = record.startedAt,
          lastHeartbeatAt = record.lastHeartbeatAt,
          endedAt = record.endedAt,
          durationMs = record.durationMs,
          startPage = record.startPage,
          lastPage = record.lastPage,
          pagesRead = record.pagesRead,
          deviceId = record.deviceId.orEmpty(),
          deviceName = record.deviceName.orEmpty(),
          client = record.client.orEmpty(),
        )
      }
  }

  fun countSessions(
    deviceId: String?,
    from: LocalDateTime?,
    to: LocalDateTime?,
  ): Int {
    var condition = DSL.noCondition()
    if (!deviceId.isNullOrBlank()) condition = condition.and(s.DEVICE_ID.eq(deviceId))
    if (from != null) condition = condition.and(s.STARTED_AT.ge(from))
    if (to != null) condition = condition.and(s.STARTED_AT.le(to))
    return dslRO.fetchCount(s, condition)
  }
}

data class LantaiDeviceRow(
  val deviceId: String,
  val deviceName: String?,
  val client: String?,
  val sessionCount: Int,
  val durationMs: Long,
  val pagesRead: Int,
  val bookCount: Int,
  val lastActiveAt: LocalDateTime?,
  val firstSeenAt: LocalDateTime?,
)

data class LantaiSessionRow(
  val id: String,
  val userId: String,
  val bookId: String,
  val seriesId: String,
  val libraryId: String,
  val startedAt: LocalDateTime,
  val lastHeartbeatAt: LocalDateTime,
  val endedAt: LocalDateTime?,
  val durationMs: Long,
  val startPage: Int,
  val lastPage: Int,
  val pagesRead: Int,
  val deviceId: String,
  val deviceName: String,
  val client: String,
)
