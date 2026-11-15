package org.lantai.infrastructure.jooq.main

import org.jooq.DSLContext
import org.lantai.infrastructure.jooq.SplitDslDaoBase
import org.lantai.jooq.main.Tables
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

@Component
class LantaiExportQueryDao(
  dslRW: DSLContext,
  @Qualifier("dslContextRO") dslRO: DSLContext,
) : SplitDslDaoBase(dslRW, dslRO) {
  fun libraries(): List<LantaiExportLibraryRow> {
    val l = Tables.LIBRARY
    return dslRO
      .select(l.ID, l.NAME, l.ROOT)
      .from(l)
      .orderBy(l.NAME)
      .fetch { LantaiExportLibraryRow(id = it.value1(), name = it.value2(), root = it.value3()) }
  }

  fun series(): List<LantaiExportSeriesRow> {
    val s = Tables.SERIES
    return dslRO
      .select(s.ID, s.NAME, s.LIBRARY_ID, s.BOOK_COUNT, s.URL, s.DELETED_DATE, s.EXCLUDED_DATE)
      .from(s)
      .orderBy(s.LIBRARY_ID, s.NAME)
      .fetch {
        LantaiExportSeriesRow(
          id = it.value1(),
          name = it.value2(),
          libraryId = it.value3(),
          bookCount = it.value4() ?: 0,
          url = it.value5(),
          deleted = it.value6() != null,
          excluded = it.value7() != null,
        )
      }
  }

  fun books(): List<LantaiExportBookRow> {
    val b = Tables.BOOK
    return dslRO
      .select(b.ID, b.NAME, b.SERIES_ID, b.LIBRARY_ID, b.NUMBER, b.FILE_SIZE, b.URL, b.DELETED_DATE, b.EXCLUDED_DATE)
      .from(b)
      .orderBy(b.LIBRARY_ID, b.SERIES_ID, b.NUMBER)
      .fetch {
        LantaiExportBookRow(
          id = it.value1(),
          name = it.value2(),
          seriesId = it.value3(),
          libraryId = it.value4(),
          number = it.value5() ?: 0,
          fileSize = it.value6() ?: 0L,
          url = it.value7(),
          deleted = it.value8() != null,
          excluded = it.value9() != null,
        )
      }
  }
}

data class LantaiExportLibraryRow(
  val id: String,
  val name: String,
  val root: String,
)

data class LantaiExportSeriesRow(
  val id: String,
  val name: String,
  val libraryId: String,
  val bookCount: Int,
  val url: String,
  val deleted: Boolean,
  val excluded: Boolean,
)

data class LantaiExportBookRow(
  val id: String,
  val name: String,
  val seriesId: String,
  val libraryId: String,
  val number: Int,
  val fileSize: Long,
  val url: String,
  val deleted: Boolean,
  val excluded: Boolean,
)
