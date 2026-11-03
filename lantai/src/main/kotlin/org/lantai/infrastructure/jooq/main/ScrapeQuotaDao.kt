package org.lantai.infrastructure.jooq.main

import org.lantai.domain.persistence.ScrapeQuotaRepository
import org.lantai.infrastructure.jooq.SplitDslDaoBase
import org.lantai.jooq.main.Tables
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Component
class ScrapeQuotaDao(
  dslRW: DSLContext,
  @Qualifier("dslContextRO") dslRO: DSLContext,
) : SplitDslDaoBase(dslRW, dslRO),
  ScrapeQuotaRepository {
  private val q = Tables.SCRAPE_QUOTA

  private val formatter = DateTimeFormatter.ISO_LOCAL_DATE

  override fun usage(
    source: String,
    day: LocalDate,
    purpose: String,
  ): Int =
    dslRO
      .select(q.COUNT)
      .from(q)
      .where(q.SOURCE.eq(source))
      .and(q.DAY.eq(formatter.format(day)))
      .and(q.PURPOSE.eq(purpose))
      .fetchOne(q.COUNT) ?: 0

  override fun increment(
    source: String,
    day: LocalDate,
    purpose: String,
  ) {
    val dayKey = formatter.format(day)
    val updated =
      dslRW
        .update(q)
        .set(q.COUNT, q.COUNT.plus(1))
        .where(q.SOURCE.eq(source))
        .and(q.DAY.eq(dayKey))
        .and(q.PURPOSE.eq(purpose))
        .execute()
    if (updated == 0) {
      dslRW
        .insertInto(q)
        .set(q.SOURCE, source)
        .set(q.DAY, dayKey)
        .set(q.PURPOSE, purpose)
        .set(q.COUNT, 1)
        .execute()
    }
  }

  override fun deleteBefore(day: LocalDate): Int =
    dslRW
      .deleteFrom(q)
      .where(q.DAY.lt(formatter.format(day)))
      .execute()
}
