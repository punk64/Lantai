package org.lantai.infrastructure.jooq.main

import org.jooq.DSLContext
import org.jooq.impl.DSL.max
import org.lantai.domain.model.ReadingStatsDaily
import org.lantai.domain.persistence.ReadingStatsDailyRepository
import org.lantai.infrastructure.jooq.SplitDslDaoBase
import org.lantai.jooq.main.Tables
import org.lantai.jooq.main.tables.records.ReadingStatsDailyRecord
import org.lantai.language.toCurrentTimeZone
import org.lantai.language.toUTC
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.LocalDateTime

@Component
class ReadingStatsDailyDao(
  dslRW: DSLContext,
  @Qualifier("dslContextRO") dslRO: DSLContext,
) : SplitDslDaoBase(dslRW, dslRO),
  ReadingStatsDailyRepository {
  private val d = Tables.READING_STATS_DAILY

  override fun replaceRange(
    userId: String,
    from: LocalDate,
    to: LocalDate,
    rows: Collection<ReadingStatsDaily>,
  ) {
    dslRW.transaction { cfg ->
      cfg
        .dsl()
        .deleteFrom(d)
        .where(d.USER_ID.eq(userId))
        .and(d.DAY.between(from, to))
        .execute()

      if (rows.isNotEmpty()) {
        val now = LocalDateTime.now().toUTC()
        cfg
          .dsl()
          .batch(
            rows.map { row ->
              cfg
                .dsl()
                .insertInto(
                  d,
                  d.USER_ID,
                  d.DAY,
                  d.BOOK_ID,
                  d.BOOK_NAME,
                  d.SERIES_ID,
                  d.LIBRARY_ID,
                  d.DURATION_MS,
                  d.PAGES_READ,
                  d.SESSION_COUNT,
                  d.LAST_READ_AT,
                  d.LAST_MODIFIED_DATE,
                ).values(
                  row.userId,
                  row.day,
                  row.bookId,
                  row.bookName,
                  row.seriesId,
                  row.libraryId,
                  row.durationMs,
                  row.pagesRead,
                  row.sessionCount,
                  row.lastReadAt?.toUTC(),
                  now,
                )
            },
          ).execute()
      }
    }
  }

  override fun findAllByUserIdAndDayBetween(
    userId: String,
    from: LocalDate,
    to: LocalDate,
  ): Collection<ReadingStatsDaily> =
    dslRO
      .selectFrom(d)
      .where(d.USER_ID.eq(userId))
      .and(d.DAY.between(from, to))
      .fetchInto(d)
      .map { it.toDomain() }

  override fun maxDay(userId: String): LocalDate? =
    dslRO
      .select(max(d.DAY))
      .from(d)
      .where(d.USER_ID.eq(userId))
      .fetchOne(0, LocalDate::class.java)

  override fun countByUserId(userId: String): Int =
    dslRO
      .selectCount()
      .from(d)
      .where(d.USER_ID.eq(userId))
      .fetchOne(0, Int::class.java) ?: 0
}

private fun ReadingStatsDailyRecord.toDomain() =
  ReadingStatsDaily(
    userId = userId,
    day = day,
    bookId = bookId,
    bookName = bookName ?: "",
    seriesId = seriesId ?: "",
    libraryId = libraryId ?: "",
    durationMs = durationMs,
    pagesRead = pagesRead,
    sessionCount = sessionCount,
    lastReadAt = lastReadAt?.toCurrentTimeZone(),
  )
