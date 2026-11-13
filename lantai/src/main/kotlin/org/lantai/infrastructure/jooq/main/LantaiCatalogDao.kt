package org.lantai.infrastructure.jooq.main

import org.jooq.DSLContext
import org.lantai.domain.model.Media
import org.lantai.infrastructure.jooq.SplitDslDaoBase
import org.lantai.jooq.main.Tables
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

@Component
class LantaiCatalogDao(
  dslRW: DSLContext,
  @Qualifier("dslContextRO") dslRO: DSLContext,
) : SplitDslDaoBase(dslRW, dslRO) {
  fun readyCatalog(): ReadyCatalog {
    val b = Tables.BOOK
    val m = Tables.MEDIA
    val ready =
      m.STATUS
        .eq(Media.Status.READY.name)
        .and(b.DELETED_DATE.isNull)
        .and(b.EXCLUDED_DATE.isNull)
    val seriesIds =
      dslRO
        .selectDistinct(b.SERIES_ID)
        .from(b)
        .join(m)
        .on(m.BOOK_ID.eq(b.ID))
        .where(ready)
        .fetch(b.SERIES_ID)
    val bookCount =
      dslRO
        .selectCount()
        .from(b)
        .join(m)
        .on(m.BOOK_ID.eq(b.ID))
        .where(ready)
        .fetchOne()
        ?.value1() ?: 0
    return ReadyCatalog(seriesIds = seriesIds, bookCount = bookCount)
  }
}

data class ReadyCatalog(
  val seriesIds: List<String>,
  val bookCount: Int,
)
