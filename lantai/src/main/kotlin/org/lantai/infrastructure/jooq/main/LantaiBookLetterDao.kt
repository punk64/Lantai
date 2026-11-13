package org.lantai.infrastructure.jooq.main

import org.jooq.DSLContext
import org.lantai.domain.model.Media
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
  private val b = Tables.BOOK
  private val m = Tables.MEDIA
  private val d = Tables.BOOK_METADATA

  private data class Entry(
    val bookId: String,
    val libraryId: String,
    val letter: String,
  )

  private val cacheLock = Any()
  private var cacheAt = 0L
  private var cache: List<Entry> = emptyList()

  private fun lettered(): List<Entry> =
    synchronized(cacheLock) {
      val now = System.currentTimeMillis()
      if (cache.isNotEmpty() && now - cacheAt < CACHE_TTL_MS) return cache
      cache =
        dslRO
          .select(d.BOOK_ID, b.LIBRARY_ID, d.TITLE)
          .from(d)
          .join(b)
          .on(b.ID.eq(d.BOOK_ID))
          .join(m)
          .on(m.BOOK_ID.eq(b.ID))
          .where(b.DELETED_DATE.isNull)
          .and(m.STATUS.`in`(Media.Status.READY.name, Media.Status.UNSUPPORTED.name))
          .fetch()
          .map { Entry(it.value1(), it.value2(), SeriesFirstLetter.of(it.value3())) }
      cacheAt = now
      cache
    }

  fun groups(libraryId: String? = null): List<Map<String, Any>> =
    lettered()
      .filter { libraryId.isNullOrBlank() || it.libraryId == libraryId }
      .groupingBy { it.letter }
      .eachCount()
      .entries
      .sortedWith(compareBy({ it.key == "#" }, { it.key }))
      .map { mapOf<String, Any>("group" to it.key, "count" to it.value) }

  fun idsByLetter(
    letter: String,
    libraryId: String? = null,
  ): List<String> {
    val key = SeriesFirstLetter.normalizeLetter(letter)
    return lettered()
      .filter { it.letter == key && (libraryId.isNullOrBlank() || it.libraryId == libraryId) }
      .map { it.bookId }
  }

  companion object {
    private const val CACHE_TTL_MS = 30_000L
  }
}
