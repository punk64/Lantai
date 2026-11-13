package org.lantai.domain.persistence

import org.lantai.domain.model.ReadingSession
import java.time.LocalDateTime

interface ReadingSessionRepository {
  fun upsert(session: ReadingSession)

  fun insertIfAbsent(session: ReadingSession): Boolean

  fun findAll(): Collection<ReadingSession>

  fun findAllByUserIdAndStartedAtBetween(
    userId: String,
    from: LocalDateTime,
    to: LocalDateTime,
  ): Collection<ReadingSession>

  fun earliestStartedAt(userId: String): LocalDateTime?

  fun distinctUserIds(): Collection<String>

  fun deleteByUserIdAndStartedAtBefore(
    userId: String,
    cutoff: LocalDateTime,
  ): Int
}
