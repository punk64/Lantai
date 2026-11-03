package org.lantai.infrastructure.jooq.main

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.lantai.domain.model.ScrapeCandidate
import org.lantai.domain.model.ScrapeMatch
import org.lantai.domain.persistence.ScrapeMatchRepository
import org.lantai.infrastructure.jooq.SplitDslDaoBase
import org.lantai.jooq.main.Tables
import org.lantai.jooq.main.tables.records.ScrapeMatchRecord
import org.lantai.language.toCurrentTimeZone
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.time.ZoneId

@Component
class ScrapeMatchDao(
  dslRW: DSLContext,
  @Qualifier("dslContextRO") dslRO: DSLContext,
  private val objectMapper: ObjectMapper,
) : SplitDslDaoBase(dslRW, dslRO),
  ScrapeMatchRepository {
  private val m = Tables.SCRAPE_MATCH

  override fun insert(match: ScrapeMatch) {
    val now = LocalDateTime.now(ZoneId.of("Z"))
    dslRW
      .insertInto(m)
      .set(m.ID, match.id)
      .set(m.SERIES_ID, match.seriesId)
      .set(m.SERIES_TITLE, match.seriesTitle)
      .set(m.SOURCE, match.source)
      .set(m.SOURCE_ID, match.sourceId)
      .set(m.SCORE, match.score.toFloat())
      .set(m.STATUS, match.status.name)
      .set(m.CANDIDATE, objectMapper.writeValueAsString(match.candidate))
      .set(m.SNAPSHOT, match.snapshot)
      .set(m.APPLIED_FIELDS, match.appliedFields.joinToString(","))
      .set(m.CREATED_DATE, now)
      .set(m.LAST_MODIFIED_DATE, now)
      .execute()
  }

  override fun findByIdOrNull(id: String): ScrapeMatch? =
    dslRO
      .selectFrom(m)
      .where(m.ID.eq(id))
      .fetchOne()
      ?.toDomain()

  override fun findAllByStatus(status: ScrapeMatch.Status): List<ScrapeMatch> =
    dslRO
      .selectFrom(m)
      .where(m.STATUS.eq(status.name))
      .orderBy(m.SCORE.desc(), m.CREATED_DATE.asc())
      .fetch()
      .map { it.toDomain() }

  override fun findBySeriesId(seriesId: String): List<ScrapeMatch> =
    dslRO
      .selectFrom(m)
      .where(m.SERIES_ID.eq(seriesId))
      .orderBy(m.CREATED_DATE.desc())
      .fetch()
      .map { it.toDomain() }

  override fun update(match: ScrapeMatch) {
    dslRW
      .update(m)
      .set(m.STATUS, match.status.name)
      .set(m.SNAPSHOT, match.snapshot)
      .set(m.APPLIED_FIELDS, match.appliedFields.joinToString(","))
      .set(m.SCORE, match.score.toFloat())
      .set(m.LAST_MODIFIED_DATE, LocalDateTime.now(ZoneId.of("Z")))
      .where(m.ID.eq(match.id))
      .execute()
  }

  override fun deleteBySeriesId(seriesId: String) {
    dslRW
      .deleteFrom(m)
      .where(m.SERIES_ID.eq(seriesId))
      .execute()
  }

  override fun countByStatus(status: ScrapeMatch.Status): Long =
    dslRO.fetchCount(m, m.STATUS.eq(status.name)).toLong()

  private fun ScrapeMatchRecord.toDomain() =
    ScrapeMatch(
      seriesId = seriesId,
      seriesTitle = seriesTitle,
      source = source,
      sourceId = sourceId,
      score = score.toDouble(),
      status = ScrapeMatch.Status.valueOf(status),
      candidate = objectMapper.readValue<ScrapeCandidate>(candidate),
      snapshot = snapshot,
      appliedFields = appliedFields?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet(),
      id = id,
      createdDate = createdDate.toCurrentTimeZone(),
      lastModifiedDate = lastModifiedDate.toCurrentTimeZone(),
    )
}
