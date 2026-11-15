package org.lantai.domain.persistence

import org.lantai.domain.model.ReadingStatsDaily
import java.time.LocalDate

interface ReadingStatsDailyRepository {
  fun replaceRange(
    userId: String,
    from: LocalDate,
    to: LocalDate,
    rows: Collection<ReadingStatsDaily>,
  )

  fun findAllByUserIdAndDayBetween(
    userId: String,
    from: LocalDate,
    to: LocalDate,
  ): Collection<ReadingStatsDaily>

  fun maxDay(userId: String): LocalDate?

  fun countByUserId(userId: String): Int
}
