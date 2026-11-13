package org.lantai.infrastructure.jooq.main

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.lantai.domain.model.Media
import org.lantai.infrastructure.jooq.SplitDslDaoBase
import org.lantai.jooq.main.Tables
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

@Component
class LantaiSeriesVolumeDao(
  dslRW: DSLContext,
  @Qualifier("dslContextRO") dslRO: DSLContext,
) : SplitDslDaoBase(dslRW, dslRO) {
  private val b = Tables.BOOK
  private val d = Tables.BOOK_METADATA
  private val m = Tables.MEDIA
  private val r = Tables.READ_PROGRESS
  private val ns = DSL.coalesce(d.NUMBER_SORT, DSL.inline(0))
  private val readable = m.STATUS.`in`(Media.Status.READY.name, Media.Status.UNSUPPORTED.name)

  data class VolumeRow(
    val name: String,
    val count: Int,
    val order: Int,
  )

  data class NeighborRow(
    val previousId: String?,
    val nextId: String?,
  )

  fun volumes(seriesId: String): List<VolumeRow> =
    dslRO
      .select(b.VOLUME, DSL.count(), DSL.min(ns))
      .from(b)
      .join(m)
      .on(m.BOOK_ID.eq(b.ID))
      .leftJoin(d)
      .on(d.BOOK_ID.eq(b.ID))
      .where(b.SERIES_ID.eq(seriesId))
      .and(b.DELETED_DATE.isNull)
      .and(readable)
      .groupBy(b.VOLUME)
      .orderBy(DSL.min(ns).asc(), b.VOLUME.asc())
      .fetch()
      .map { VolumeRow(it.value1(), it.value2(), (it.value3() ?: 0).toInt()) }

  fun totalSize(seriesId: String): Long =
    dslRO
      .select(DSL.coalesce(DSL.sum(b.FILE_SIZE), DSL.inline(0L)))
      .from(b)
      .join(m)
      .on(m.BOOK_ID.eq(b.ID))
      .where(b.SERIES_ID.eq(seriesId))
      .and(b.DELETED_DATE.isNull)
      .and(readable)
      .fetchOne(0, Long::class.java) ?: 0L

  fun continueBookId(
    seriesId: String,
    userId: String,
  ): String? =
    dslRO
      .select(b.ID)
      .from(b)
      .join(r)
      .on(r.BOOK_ID.eq(b.ID))
      .where(b.SERIES_ID.eq(seriesId))
      .and(b.DELETED_DATE.isNull)
      .and(r.USER_ID.eq(userId))
      .orderBy(r.LAST_MODIFIED_DATE.desc())
      .limit(1)
      .fetchOne(b.ID)

  fun neighbors(bookId: String): NeighborRow? {
    val current =
      dslRO
        .select(b.SERIES_ID, ns, b.NAME)
        .from(b)
        .join(m)
        .on(m.BOOK_ID.eq(b.ID))
        .leftJoin(d)
        .on(d.BOOK_ID.eq(b.ID))
        .where(b.ID.eq(bookId))
        .fetchOne() ?: return null
    val seriesId = current.value1()
    val numberSort = current.value2()
    val name = current.value3()
    val scope = b.SERIES_ID.eq(seriesId).and(b.DELETED_DATE.isNull).and(readable)
    val nextId =
      dslRO
        .select(b.ID)
        .from(b)
        .join(m)
        .on(m.BOOK_ID.eq(b.ID))
        .leftJoin(d)
        .on(d.BOOK_ID.eq(b.ID))
        .where(scope)
        .and(ns.gt(numberSort).or(ns.eq(numberSort).and(b.NAME.gt(name))))
        .orderBy(ns.asc(), b.NAME.asc())
        .limit(1)
        .fetchOne(b.ID)
    val previousId =
      dslRO
        .select(b.ID)
        .from(b)
        .join(m)
        .on(m.BOOK_ID.eq(b.ID))
        .leftJoin(d)
        .on(d.BOOK_ID.eq(b.ID))
        .where(scope)
        .and(ns.lt(numberSort).or(ns.eq(numberSort).and(b.NAME.lt(name))))
        .orderBy(ns.desc(), b.NAME.desc())
        .limit(1)
        .fetchOne(b.ID)
    return NeighborRow(previousId, nextId)
  }
}
