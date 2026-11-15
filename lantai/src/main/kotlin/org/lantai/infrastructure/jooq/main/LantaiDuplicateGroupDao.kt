package org.lantai.infrastructure.jooq.main

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.lantai.infrastructure.jooq.SplitDslDaoBase
import org.lantai.jooq.main.Tables
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

@Component
class LantaiDuplicateGroupDao(
  dslRW: DSLContext,
  @Qualifier("dslContextRO") dslRO: DSLContext,
) : SplitDslDaoBase(dslRW, dslRO) {
  private val b = Tables.BOOK

  fun groups(page: Int, size: Int): Map<String, Any> {
    val counter = DSL.count()
    val base = b.DELETED_DATE.isNull.and(b.FILE_HASH.ne(""))
    val grouped =
      dslRO
        .select(b.FILE_HASH)
        .from(b)
        .where(base)
        .groupBy(b.FILE_HASH)
        .having(counter.gt(1))
    val total = dslRO.select(DSL.count()).from(grouped).fetchOne(0, Int::class.java) ?: 0
    val rows =
      dslRO
        .select(b.FILE_HASH, counter)
        .from(b)
        .where(base)
        .groupBy(b.FILE_HASH)
        .having(counter.gt(1))
        .orderBy(counter.desc(), b.FILE_HASH.asc())
        .limit(size)
        .offset(page * size)
        .fetch()
    val hashes = rows.mapNotNull { it.value1() }
    val books =
      if (hashes.isEmpty()) {
        emptyMap()
      } else {
        dslRO
          .select(b.ID, b.NAME, b.FILE_HASH, b.FILE_SIZE, b.SERIES_ID)
          .from(b)
          .where(base.and(b.FILE_HASH.`in`(hashes)))
          .fetch()
          .groupBy { it.value3() }
      }
    return linkedMapOf(
      "page" to page,
      "size" to size,
      "totalGroups" to total,
      "groups" to
        rows.map { row ->
          val hash = row.value1()
          linkedMapOf(
            "fileHash" to hash,
            "count" to (row.value2() ?: 0),
            "books" to
              books[hash].orEmpty().map { record ->
                linkedMapOf(
                  "id" to record.value1(),
                  "name" to record.value2(),
                  "sizeBytes" to (record.value4() ?: 0L),
                  "seriesId" to record.value5(),
                )
              },
          )
        },
    )
  }
}
