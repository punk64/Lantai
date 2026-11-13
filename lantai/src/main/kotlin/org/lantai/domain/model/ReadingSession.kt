package org.lantai.domain.model

import java.time.LocalDateTime

data class ReadingSession(
  val id: String,
  val userId: String,
  val bookId: String,
  val seriesId: String,
  val libraryId: String,
  val startedAt: LocalDateTime,
  val lastHeartbeatAt: LocalDateTime,
  val endedAt: LocalDateTime? = null,
  val durationMs: Long = 0,
  val startPage: Int = 0,
  val lastPage: Int = 0,
  val pagesRead: Int = 0,
  val deviceId: String = "",
  val deviceName: String = "",
  val client: String = "",
  val clientSessionId: String? = null,
  val syncedAt: LocalDateTime? = null,
)
