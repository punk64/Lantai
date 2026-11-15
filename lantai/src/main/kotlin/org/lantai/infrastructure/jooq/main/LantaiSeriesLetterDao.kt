package org.lantai.infrastructure.jooq.main

import org.jooq.DSLContext
import org.lantai.infrastructure.i18n.SeriesFirstLetter
import org.lantai.infrastructure.jooq.SplitDslDaoBase
import org.lantai.jooq.main.Tables
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

@Component
class LantaiSeriesLetterDao(
  dslRW: DSLContext,
  @Qualifier("dslContextRO") dslRO: DSLContext,
) : SplitDslDaoBase(dslRW, dslRO) {
  private val s = Tables.SERIES
  private val d = Tables.SERIES_METADATA

  fun idsByLetter(
    letter: String,
    libraryId: String? = null,
  ): List<String> {
    val key = SeriesFirstLetter.normalizeLetter(letter)
    var condition = d.FIRST_LETTER.eq(key).and(s.DELETED_DATE.isNull)
    if (!libraryId.isNullOrBlank()) condition = condition.and(s.LIBRARY_ID.eq(libraryId))
    return dslRO
      .select(d.SERIES_ID)
      .from(d)
      .join(s)
      .on(s.ID.eq(d.SERIES_ID))
      .where(condition)
      .fetch(d.SERIES_ID)
  }

  fun rebuild(seriesId: String): String {
    val titleSort = dslRO.select(d.TITLE_SORT).from(d).where(d.SERIES_ID.eq(seriesId)).fetchOne()?.get(d.TITLE_SORT) ?: ""
    val letter = SeriesFirstLetter.of(titleSort)
    dslRW.update(d).set(d.FIRST_LETTER, letter).where(d.SERIES_ID.eq(seriesId)).execute()
    return letter
  }
}
