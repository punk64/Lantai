package org.lantai.infrastructure.jooq.main

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.lantai.domain.model.MediaType
import org.lantai.infrastructure.jooq.SplitDslDaoBase
import org.lantai.jooq.main.Tables
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

@Component
class LantaiCatalogFacetDao(
  dslRW: DSLContext,
  @Qualifier("dslContextRO") dslRO: DSLContext,
) : SplitDslDaoBase(dslRW, dslRO) {
  private val s = Tables.SERIES
  private val d = Tables.SERIES_METADATA
  private val g = Tables.SERIES_METADATA_GENRE
  private val b = Tables.BOOK
  private val m = Tables.MEDIA
  private val bma = Tables.BOOK_METADATA_AGGREGATION

  fun facets(libraryId: String? = null): Map<String, Any> {
    val base = baseCondition(libraryId)
    return linkedMapOf(
      "total" to countSeries(base),
      "genres" to groupValues(g.GENRE, g.SERIES_ID, base),
      "publishers" to groupValues(d.PUBLISHER, d.SERIES_ID, base, d.PUBLISHER.ne("")),
      "onlinePublishers" to groupValues(d.PUBLISHER, d.SERIES_ID, onlineOnly(base), d.PUBLISHER.ne("")),
      "statuses" to groupValues(d.STATUS, d.SERIES_ID, base),
      "regions" to groupValues(d.REGION, d.SERIES_ID, base),
      "years" to years(libraryId),
      "formats" to formats(base),
      "scrapeStates" to scrapeStates(base),
    )
  }

  private fun baseCondition(libraryId: String?): org.jooq.Condition {
    val ready =
      DSL.exists(
        DSL
          .select(DSL.one())
          .from(b)
          .join(m)
          .on(m.BOOK_ID.eq(b.ID))
          .where(b.SERIES_ID.eq(s.ID))
          .and(m.STATUS.`in`("READY", "UNSUPPORTED")),
      )
    var condition = s.DELETED_DATE.isNull.and(ready)
    if (!libraryId.isNullOrBlank()) condition = condition.and(s.LIBRARY_ID.eq(libraryId))
    return condition
  }

  private fun onlineOnly(base: org.jooq.Condition): org.jooq.Condition =
    base.and(
      DSL.exists(
        DSL
          .select(DSL.one())
          .from(Tables.LIBRARY)
          .where(Tables.LIBRARY.ID.eq(s.LIBRARY_ID))
          .and(Tables.LIBRARY.TYPE.eq("ONLINE")),
      ),
    )

  private fun countSeries(base: org.jooq.Condition): Int =
    dslRO.select(DSL.countDistinct(s.ID)).from(s).where(base).fetchOne(0, Int::class.java) ?: 0

  private fun groupValues(
    value: org.jooq.Field<String>,
    seriesId: org.jooq.Field<String>,
    base: org.jooq.Condition,
    extra: org.jooq.Condition? = null,
  ): List<Map<String, Any>> {
    var condition = base
    if (extra != null) condition = condition.and(extra)
    val counter = DSL.countDistinct(s.ID)
    return dslRO
      .select(value, counter)
      .from(s)
      .join(Tables.SERIES_METADATA)
      .on(Tables.SERIES_METADATA.SERIES_ID.eq(s.ID))
      .leftJoin(g)
      .on(g.SERIES_ID.eq(s.ID))
      .where(condition)
      .groupBy(value)
      .orderBy(counter.desc())
      .fetch()
      .mapNotNull { record ->
        val v = record.value1() ?: return@mapNotNull null
        if (v.isBlank()) return@mapNotNull null
        mapOf("value" to v, "count" to (record.value2() ?: 0))
      }
  }

  private fun years(libraryId: String?): List<Map<String, Any>> {
    val yearField = DSL.substring(bma.RELEASE_DATE.cast(String::class.java), 1, 4)
    var condition = s.DELETED_DATE.isNull.and(bma.RELEASE_DATE.isNotNull)
    if (!libraryId.isNullOrBlank()) condition = condition.and(s.LIBRARY_ID.eq(libraryId))
    val counter = DSL.countDistinct(s.ID)
    return dslRO
      .select(yearField, counter)
      .from(s)
      .join(bma)
      .on(bma.SERIES_ID.eq(s.ID))
      .where(condition)
      .groupBy(yearField)
      .orderBy(yearField.desc())
      .fetch()
      .mapNotNull { record ->
        val v = record.value1() ?: return@mapNotNull null
        if (!v.matches(Regex("^[0-9]{4}$"))) return@mapNotNull null
        val year = v.toIntOrNull() ?: return@mapNotNull null
        if (year < 1000 || year > 2100) return@mapNotNull null
        mapOf("value" to v, "count" to (record.value2() ?: 0))
      }
  }

  private fun formats(base: org.jooq.Condition): List<Map<String, Any>> =
    listOf(
      "cbx" to org.lantai.domain.model.MediaProfile.DIVINA,
      "epub" to org.lantai.domain.model.MediaProfile.EPUB,
      "pdf" to org.lantai.domain.model.MediaProfile.PDF,
      "txt" to org.lantai.domain.model.MediaProfile.TXT,
    ).map { (label, profile) ->
      val types = MediaType.matchingMediaProfile(profile).map { it.type }.toSet()
      val has =
        DSL.exists(
          DSL
            .select(DSL.one())
            .from(b)
            .join(m)
            .on(m.BOOK_ID.eq(b.ID))
            .where(b.SERIES_ID.eq(s.ID))
            .and(m.MEDIA_TYPE.`in`(types)),
        )
      val count = dslRO.select(DSL.countDistinct(s.ID)).from(s).where(base.and(has)).fetchOne(0, Int::class.java) ?: 0
      mapOf<String, Any>("value" to label, "count" to count)
    }

  private fun scrapeStates(base: org.jooq.Condition): List<Map<String, Any>> {
    val mediaBad =
      DSL
        .select(DSL.one())
        .from(b)
        .join(m)
        .on(m.BOOK_ID.eq(b.ID))
        .where(b.SERIES_ID.eq(s.ID))
        .and(m.STATUS.notIn("READY", "OUTDATED"))
    val bad = DSL.exists(mediaBad)
    val filled =
      d.PUBLISHER
        .ne("")
        .or(d.REGION.ne(""))
        .or(d.LANGUAGE.ne(""))
        .or(d.SUMMARY.ne(""))
        .or(DSL.exists(DSL.select(DSL.one()).from(g).where(g.SERIES_ID.eq(s.ID))))
    return listOf(
      "bad" to bad,
      "done" to bad.not().and(filled),
      "none" to bad.not().and(filled.not()),
    ).map { (label, condition) ->
      val count =
        dslRO
          .select(DSL.countDistinct(s.ID))
          .from(s)
          .leftJoin(d)
          .on(d.SERIES_ID.eq(s.ID))
          .where(base.and(condition))
          .fetchOne(0, Int::class.java) ?: 0
      mapOf<String, Any>("value" to label, "count" to count)
    }
  }
}
