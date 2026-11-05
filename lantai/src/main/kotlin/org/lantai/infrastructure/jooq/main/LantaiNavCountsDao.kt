package org.lantai.infrastructure.jooq.main

import org.jooq.DSLContext
import org.lantai.infrastructure.jooq.SplitDslDaoBase
import org.lantai.jooq.main.Tables
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

@Component
class LantaiNavCountsDao(
  dslRW: DSLContext,
  @Qualifier("dslContextRO") dslRO: DSLContext,
) : SplitDslDaoBase(dslRW, dslRO) {
  fun counts(userId: String): NavCounts {
    val a = Tables.BOOK_ANNOTATION
    val b = Tables.BOOK
    val s = Tables.SERIES
    return NavCounts(
      libraries = dslRO.fetchCount(Tables.LIBRARY),
      series = dslRO.fetchCount(s, s.DELETED_DATE.isNull),
      books = dslRO.fetchCount(b, b.DELETED_DATE.isNull),
      collections = dslRO.fetchCount(Tables.COLLECTION),
      readlists = dslRO.fetchCount(Tables.READLIST),
      annotations = dslRO.fetchCount(a, a.USER_ID.eq(userId)),
      onlineSeries = dslRO.fetchCount(Tables.ONLINE_MANGA),
      scrapeQueued = scrapeQueuedBooks(),
    )
  }

  private fun scrapeQueuedBooks(): Int {
    val q = Tables.SCRAPE_QUEUE
    val s = Tables.SERIES
    val rows = dslRO.select(q.SERIES_ID, q.BOOK_IDS).from(q).fetch()
    val wholeSeries =
      rows
        .filter { it.value2().isNullOrBlank() }
        .mapNotNull { it.value1() }
        .distinct()
    val seriesBooks =
      if (wholeSeries.isEmpty()) {
        emptyMap()
      } else {
        dslRO
          .select(s.ID, s.BOOK_COUNT)
          .from(s)
          .where(s.ID.`in`(wholeSeries))
          .fetch()
          .associate { it.value1() to it.value2().toInt() }
      }
    return rows.sumOf { row ->
      val scoped = row.value2()?.split(",")?.count { it.isNotBlank() } ?: 0
      if (scoped > 0) scoped else seriesBooks[row.value1()] ?: 0
    }
  }
}

data class NavCounts(
  val libraries: Int,
  val series: Int,
  val books: Int,
  val collections: Int,
  val readlists: Int,
  val annotations: Int,
  val onlineSeries: Int,
  val scrapeQueued: Int,
)
