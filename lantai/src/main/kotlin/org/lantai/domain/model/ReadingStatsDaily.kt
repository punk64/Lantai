package org.lantai.domain.model

import java.time.LocalDate
import java.time.LocalDateTime

data class ReadingStatsDaily(
  val userId: String,
  val day: LocalDate,
  val bookId: String,
  val bookName: String = "",
  val seriesId: String = "",
  val libraryId: String = "",
  val durationMs: Long = 0,
  val pagesRead: Int = 0,
  val sessionCount: Int = 0,
  val lastReadAt: LocalDateTime? = null,
)
