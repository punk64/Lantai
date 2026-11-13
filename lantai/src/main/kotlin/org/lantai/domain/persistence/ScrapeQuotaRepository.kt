package org.lantai.domain.persistence

import java.time.LocalDate

interface ScrapeQuotaRepository {
  fun usage(
    source: String,
    day: LocalDate,
    purpose: String,
  ): Int

  fun increment(
    source: String,
    day: LocalDate,
    purpose: String,
  )

  fun deleteBefore(day: LocalDate): Int
}
