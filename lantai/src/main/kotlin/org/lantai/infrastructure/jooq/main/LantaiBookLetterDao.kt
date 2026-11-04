package org.lantai.infrastructure.jooq.main

import org.jooq.DSLContext
import org.lantai.infrastructure.i18n.SeriesFirstLetter
import org.lantai.infrastructure.jooq.SplitDslDaoBase
import org.lantai.jooq.main.Tables
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

@Component
class LantaiBookLetterDao(
  dslRW: DSLContext,
  @Qualifier("dslContextRO") dslRO: DSLContext,
) : SplitDslDaoBase(dslRW, dslRO) {
  private val d = Tables.BOOK_METADATA

  private fun lettered(): List<Pair<String, String>> =
    dslRO
      .select(d.BOOK_ID, d.TITLE)
      .from(d)
      .fetch()
      .map { it.value1() to SeriesFirstLetter.of(it.value2()) }

  fun groups(): List<Map<String, Any>> =
    lettered()
      .groupingBy { it.second }
      .eachCount()
      .entries
      .sortedWith(compareBy({ it.key == "#" }, { it.key }))
      .map { mapOf<String, Any>("group" to it.key, "count" to it.value) }

  fun idsByLetter(letter: String): List<String> {
    val key = SeriesFirstLetter.normalizeLetter(letter)
    return lettered().filter { it.second == key }.map { it.first }
  }
}
