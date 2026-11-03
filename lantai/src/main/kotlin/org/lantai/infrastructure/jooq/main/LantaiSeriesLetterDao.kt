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
  private val d = Tables.SERIES_METADATA

  fun idsByLetter(letter: String): List<String> {
    val key = SeriesFirstLetter.normalizeLetter(letter)
    return dslRO
      .select(d.SERIES_ID)
      .from(d)
      .where(d.FIRST_LETTER.eq(key))
      .fetch(d.SERIES_ID)
  }

  fun rebuild(seriesId: String): String {
    val titleSort = dslRO.select(d.TITLE_SORT).from(d).where(d.SERIES_ID.eq(seriesId)).fetchOne()?.get(d.TITLE_SORT) ?: ""
    val letter = SeriesFirstLetter.of(titleSort)
    dslRW.update(d).set(d.FIRST_LETTER, letter).where(d.SERIES_ID.eq(seriesId)).execute()
    return letter
  }
}
