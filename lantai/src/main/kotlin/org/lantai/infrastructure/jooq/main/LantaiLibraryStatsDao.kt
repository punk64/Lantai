package org.lantai.infrastructure.jooq.main

import org.jooq.DSLContext
import org.jooq.impl.DSL.count
import org.jooq.impl.DSL.sum
import org.lantai.infrastructure.jooq.SplitDslDaoBase
import org.lantai.jooq.main.Tables
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

@Component
class LantaiLibraryStatsDao(
  dslRW: DSLContext,
  @Qualifier("dslContextRO") dslRO: DSLContext,
) : SplitDslDaoBase(dslRW, dslRO) {
  private val lib = Tables.LIBRARY
  private val s = Tables.SERIES
  private val b = Tables.BOOK

  fun stats(): List<LantaiLibraryStatsRow> {
    val seriesCountField = count()
    val seriesCounts =
      dslRO
        .select(s.LIBRARY_ID, seriesCountField)
        .from(s)
        .where(s.DELETED_DATE.isNull)
        .groupBy(s.LIBRARY_ID)
        .fetch()
        .associate { r -> r[s.LIBRARY_ID] to r[seriesCountField] }

    val bookCountField = count()
    val bookSizeField = sum(b.FILE_SIZE)
    val bookAgg =
      dslRO
        .select(b.LIBRARY_ID, bookCountField, bookSizeField)
        .from(b)
        .where(b.DELETED_DATE.isNull)
        .groupBy(b.LIBRARY_ID)
        .fetch()
        .associate { r -> r[b.LIBRARY_ID] to (r[bookCountField] to (r[bookSizeField] ?: 0L)) }

    return dslRO
      .select(lib.ID, lib.NAME, lib.ROOT)
      .from(lib)
      .orderBy(lib.NAME)
      .fetch { r ->
        val id = r[lib.ID]
        val agg = bookAgg[id]
        LantaiLibraryStatsRow(
          libraryId = id,
          name = r[lib.NAME],
          root = r[lib.ROOT],
          seriesCount = seriesCounts[id] ?: 0,
          bookCount = agg?.first ?: 0,
          totalBytes = agg?.second?.toLong() ?: 0L,
        )
      }
  }
}

data class LantaiLibraryStatsRow(
  val libraryId: String,
  val name: String,
  val root: String,
  val seriesCount: Int,
  val bookCount: Int,
  val totalBytes: Long,
)
