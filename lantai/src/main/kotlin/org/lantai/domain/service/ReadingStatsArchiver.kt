package org.lantai.domain.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.lantai.domain.model.ReadingStatsDaily
import org.lantai.domain.persistence.BookRepository
import org.lantai.domain.persistence.ReadingSessionRepository
import org.lantai.domain.persistence.ReadingStatsDailyRepository
import org.lantai.infrastructure.configuration.ReadingStatsProperties
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.LocalDate

private val logger = KotlinLogging.logger {}

@Service
class ReadingStatsArchiver(
  private val readingSessionRepository: ReadingSessionRepository,
  private val readingStatsDailyRepository: ReadingStatsDailyRepository,
  private val bookRepository: BookRepository,
  private val properties: ReadingStatsProperties,
) {
  val enabled: Boolean
    get() = properties.rollupEnabled

  fun ensureUpToDate(userId: String): Int = if (enabled) rollupIncremental(userId) else 0

  fun rollupIncremental(userId: String): Int {
    val yesterday = LocalDate.now().minusDays(1)
    val watermark = readingStatsDailyRepository.maxDay(userId)
    val from =
      watermark?.plusDays(1)
        ?: readingSessionRepository.earliestStartedAt(userId)?.toLocalDate()
        ?: return 0
    return rollup(userId, from, yesterday)
  }

  fun reconcile(userId: String): Int {
    val from = readingSessionRepository.earliestStartedAt(userId)?.toLocalDate() ?: return 0
    return rollup(userId, from, LocalDate.now().minusDays(1))
  }

  fun summaries(
    userId: String,
    from: LocalDate,
    to: LocalDate,
  ): Collection<ReadingStatsDaily> {
    if (!enabled || to.isBefore(from)) return emptyList()
    return readingStatsDailyRepository.findAllByUserIdAndDayBetween(userId, from, to)
  }

  fun status(userId: String): ReadingArchiveStatus {
    val earliest = readingSessionRepository.earliestStartedAt(userId)?.toLocalDate()
    return ReadingArchiveStatus(
      enabled = enabled,
      retentionDays = properties.retentionDays,
      watermark = readingStatsDailyRepository.maxDay(userId)?.toString(),
      archivedRows = readingStatsDailyRepository.countByUserId(userId),
      earliestSessionDay = earliest?.toString(),
      pendingDays = pendingDays(userId),
    )
  }

  fun purge(): Int {
    val days = properties.retentionDays
    if (days <= 0) return 0

    val cutoff = LocalDate.now().minusDays(days.toLong())
    var deleted = 0
    readingSessionRepository.distinctUserIds().forEach { userId ->
      runCatching {
        rollupIncremental(userId)
        val watermark = readingStatsDailyRepository.maxDay(userId)
        val boundary = if (watermark == null) cutoff else minOf(cutoff, watermark)
        deleted += readingSessionRepository.deleteByUserIdAndStartedAtBefore(userId, boundary.atStartOfDay())
      }.onFailure { logger.warn(it) { "Failed to purge reading sessions of user $userId" } }
    }
    return deleted
  }

  @Scheduled(cron = "0 0 4 * * *")
  fun reconcileAll() {
    if (!enabled) return
    readingSessionRepository.distinctUserIds().forEach { userId ->
      runCatching { reconcile(userId) }
        .onFailure { logger.warn(it) { "Failed to reconcile reading stats of user $userId" } }
    }
  }

  @Scheduled(cron = "0 30 4 * * *")
  fun purgeScheduled() {
    val deleted = purge()
    if (deleted > 0) logger.info { "Purged $deleted reading sessions beyond retention" }
  }

  private fun pendingDays(userId: String): Int {
    val earliest = readingSessionRepository.earliestStartedAt(userId)?.toLocalDate() ?: return 0
    val watermark = readingStatsDailyRepository.maxDay(userId)
    val from = watermark?.plusDays(1) ?: earliest
    val to = LocalDate.now().minusDays(1)
    return if (to.isBefore(from)) 0 else (to.toEpochDay() - from.toEpochDay() + 1).toInt()
  }

  private fun rollup(
    userId: String,
    fromDay: LocalDate,
    toDay: LocalDate,
  ): Int {
    if (toDay.isBefore(fromDay)) return 0

    val sessions =
      readingSessionRepository.findAllByUserIdAndStartedAtBetween(
        userId,
        fromDay.atStartOfDay(),
        toDay.plusDays(1).atStartOfDay().minusNanos(1),
      )
    val books = mutableMapOf<String, String?>()
    val rows =
      sessions
        .groupBy { it.startedAt.toLocalDate() to it.bookId }
        .map { (key, list) ->
          val (day, bookId) = key
          if (!books.containsKey(bookId)) books[bookId] = bookRepository.findByIdOrNull(bookId)?.name
          ReadingStatsDaily(
            userId = userId,
            day = day,
            bookId = bookId,
            bookName = books[bookId].orEmpty(),
            seriesId = list.first().seriesId,
            libraryId = list.first().libraryId,
            durationMs = list.sumOf { it.durationMs },
            pagesRead = list.sumOf { it.pagesRead },
            sessionCount = list.size,
            lastReadAt = list.maxOf { it.lastHeartbeatAt },
          )
        }
    readingStatsDailyRepository.replaceRange(userId, fromDay, toDay, rows)
    return rows.size
  }
}

data class ReadingArchiveStatus(
  val enabled: Boolean,
  val retentionDays: Int,
  val watermark: String? = null,
  val archivedRows: Int = 0,
  val earliestSessionDay: String? = null,
  val pendingDays: Int = 0,
)
