package org.lantai.infrastructure.jooq.main

import org.lantai.domain.model.ReadingSession
import org.lantai.domain.persistence.ReadingSessionRepository
import org.lantai.infrastructure.jooq.SplitDslDaoBase
import org.lantai.jooq.main.Tables
import org.lantai.jooq.main.tables.records.ReadingSessionRecord
import org.lantai.language.toCurrentTimeZone
import org.lantai.language.toUTC
import org.jooq.DSLContext
import org.jooq.impl.DSL.min
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.time.LocalDateTime

@Component
class ReadingSessionDao(
  dslRW: DSLContext,
  @Qualifier("dslContextRO") dslRO: DSLContext,
) : SplitDslDaoBase(dslRW, dslRO),
  ReadingSessionRepository {
  private val s = Tables.READING_SESSION

  override fun upsert(session: ReadingSession) {
    dslRW
      .insertInto(
        s,
        s.ID,
        s.USER_ID,
        s.BOOK_ID,
        s.SERIES_ID,
        s.LIBRARY_ID,
        s.STARTED_AT,
        s.LAST_HEARTBEAT_AT,
        s.ENDED_AT,
        s.DURATION_MS,
        s.START_PAGE,
        s.LAST_PAGE,
        s.PAGES_READ,
        s.DEVICE_ID,
        s.DEVICE_NAME,
        s.CLIENT,
      ).values(
        session.id,
        session.userId,
        session.bookId,
        session.seriesId,
        session.libraryId,
        session.startedAt.toUTC(),
        session.lastHeartbeatAt.toUTC(),
        session.endedAt?.toUTC(),
        session.durationMs,
        session.startPage,
        session.lastPage,
        session.pagesRead,
        session.deviceId,
        session.deviceName,
        session.client,
      ).onDuplicateKeyUpdate()
      .set(s.LAST_HEARTBEAT_AT, session.lastHeartbeatAt.toUTC())
      .set(s.ENDED_AT, session.endedAt?.toUTC())
      .set(s.DURATION_MS, session.durationMs)
      .set(s.LAST_PAGE, session.lastPage)
      .set(s.PAGES_READ, session.pagesRead)
      .execute()
  }

  override fun insertIfAbsent(session: ReadingSession): Boolean =
    dslRW
      .insertInto(
        s,
        s.ID,
        s.USER_ID,
        s.BOOK_ID,
        s.SERIES_ID,
        s.LIBRARY_ID,
        s.STARTED_AT,
        s.LAST_HEARTBEAT_AT,
        s.ENDED_AT,
        s.DURATION_MS,
        s.START_PAGE,
        s.LAST_PAGE,
        s.PAGES_READ,
        s.DEVICE_ID,
        s.DEVICE_NAME,
        s.CLIENT,
        s.CLIENT_SESSION_ID,
        s.SYNCED_AT,
      ).values(
        session.id,
        session.userId,
        session.bookId,
        session.seriesId,
        session.libraryId,
        session.startedAt.toUTC(),
        session.lastHeartbeatAt.toUTC(),
        session.endedAt?.toUTC(),
        session.durationMs,
        session.startPage,
        session.lastPage,
        session.pagesRead,
        session.deviceId,
        session.deviceName,
        session.client,
        session.clientSessionId,
        session.syncedAt?.toUTC(),
      ).onConflictDoNothing()
      .execute() > 0

  override fun findAll(): Collection<ReadingSession> =
    dslRO
      .selectFrom(s)
      .fetchInto(s)
      .map { it.toDomain() }

  override fun findAllByUserIdAndStartedAtBetween(
    userId: String,
    from: LocalDateTime,
    to: LocalDateTime,
  ): Collection<ReadingSession> =
    dslRO
      .selectFrom(s)
      .where(s.USER_ID.eq(userId))
      .and(s.STARTED_AT.between(from.toUTC(), to.toUTC()))
      .fetchInto(s)
      .map { it.toDomain() }

  override fun earliestStartedAt(userId: String): LocalDateTime? =
    dslRO
      .select(min(s.STARTED_AT))
      .from(s)
      .where(s.USER_ID.eq(userId))
      .fetchOne(0, LocalDateTime::class.java)
      ?.toCurrentTimeZone()

  override fun distinctUserIds(): Collection<String> =
    dslRO
      .selectDistinct(s.USER_ID)
      .from(s)
      .fetch(0, String::class.java)

  override fun deleteByUserIdAndStartedAtBefore(
    userId: String,
    cutoff: LocalDateTime,
  ): Int =
    dslRW
      .deleteFrom(s)
      .where(s.USER_ID.eq(userId))
      .and(s.STARTED_AT.lt(cutoff.toUTC()))
      .execute()
}

private fun ReadingSessionRecord.toDomain() =
  ReadingSession(
    id = id,
    userId = userId,
    bookId = bookId,
    seriesId = seriesId,
    libraryId = libraryId,
    startedAt = startedAt.toCurrentTimeZone(),
    lastHeartbeatAt = lastHeartbeatAt.toCurrentTimeZone(),
    endedAt = endedAt?.toCurrentTimeZone(),
    durationMs = durationMs,
    startPage = startPage,
    lastPage = lastPage,
    pagesRead = pagesRead,
    deviceId = deviceId ?: "",
    deviceName = deviceName ?: "",
    client = client ?: "",
    clientSessionId = clientSessionId,
    syncedAt = syncedAt?.toCurrentTimeZone(),
  )
