package org.lantai.domain.service

import com.github.f4b6a3.tsid.TsidCreator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.lantai.domain.model.ReadingSession
import org.lantai.domain.persistence.BookRepository
import org.lantai.domain.persistence.MediaRepository
import org.lantai.domain.persistence.ReadingSessionRepository
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

private const val MAX_HEARTBEAT_GAP_MS = 120_000L
private const val IDLE_SESSION_TIMEOUT_MINUTES = 10L
private const val MAX_SYNC_BATCH = 500
private const val MAX_OFFLINE_SESSION_MS = 86_400_000L
private const val MAX_DURATION_OVERSHOOT_RATIO = 0.05
private const val MAX_FUTURE_SKEW_MINUTES = 5L

private const val SYNC_REASON_MISSING_ID = "missing_client_session_id"
private const val SYNC_REASON_DUPLICATE_IN_REQUEST = "duplicate_in_request"
private const val SYNC_REASON_INVALID_RANGE = "invalid_time_range"
private const val SYNC_REASON_DURATION_RANGE = "duration_out_of_range"
private const val SYNC_REASON_DURATION_MISMATCH = "duration_mismatch"
private const val SYNC_REASON_FUTURE = "future_timestamp"
private const val SYNC_REASON_BOOK_NOT_FOUND = "book_not_found"
private const val SYNC_REASON_TOO_MANY = "too_many_sessions"

@Service
class ReadingStatsRecorder(
  private val readingSessionRepository: ReadingSessionRepository,
  private val bookRepository: BookRepository,
  private val mediaRepository: MediaRepository,
  private val archiver: ReadingStatsArchiver,
) {
  private val live = ConcurrentHashMap<String, LiveSession>()

  fun heartbeat(
    userId: String,
    bookId: String,
    page: Int,
    deviceId: String,
    deviceName: String,
    client: String,
  ): ReadingSessionSnapshot? {
    val book = bookRepository.findByIdOrNull(bookId) ?: return null
    val now = LocalDateTime.now()
    val session =
      live.compute(sessionKey(userId, deviceId, bookId)) { _, existing ->
        if (existing != null) {
          existing.advance(now, page)
          existing
        } else {
          LiveSession(
            id = TsidCreator.getTsid256().toString(),
            userId = userId,
            bookId = bookId,
            seriesId = book.seriesId,
            libraryId = book.libraryId,
            deviceId = deviceId,
            deviceName = deviceName,
            client = client,
            startedAt = now,
            startPage = page,
          )
        }
      }!!
    return session.snapshot()
  }

  fun notePage(
    userId: String,
    bookId: String,
    page: Int,
    deviceId: String,
    deviceName: String,
  ) {
    val book = bookRepository.findByIdOrNull(bookId) ?: return
    val now = LocalDateTime.now()
    val exactKey = sessionKey(userId, deviceId, bookId)
    val key =
      if (live.containsKey(exactKey)) {
        exactKey
      } else {
        live.keys.firstOrNull { it.startsWith("$userId|") && it.endsWith("|$bookId") } ?: exactKey
      }
    live.compute(key) { _, existing ->
      if (existing != null) {
        existing.notePage(page, now)
        existing
      } else {
        LiveSession(
          id = TsidCreator.getTsid256().toString(),
          userId = userId,
          bookId = bookId,
          seriesId = book.seriesId,
          libraryId = book.libraryId,
          deviceId = deviceId,
          deviceName = deviceName,
          client = "",
          startedAt = now,
          startPage = page,
        )
      }
    }
  }

  fun stop(
    userId: String,
    bookId: String,
    deviceId: String,
  ): ReadingSessionSnapshot? {
    val exactKey = sessionKey(userId, deviceId, bookId)
    val key =
      if (live.containsKey(exactKey)) {
        exactKey
      } else {
        live.keys.firstOrNull { it.startsWith("$userId|") && it.endsWith("|$bookId") } ?: exactKey
      }
    val session = live.remove(key) ?: return null
    session.close()
    persist(session)
    return session.snapshot()
  }

  fun activeSessions(userId: String): List<ReadingSessionSnapshot> = live.values.filter { it.userId == userId }.map { it.snapshot() }

  @Scheduled(fixedDelay = 30_000)
  fun flush() {
    val now = LocalDateTime.now()
    live.entries.toList().forEach { (key, session) ->
      if (session.isDirty()) persist(session)
      if (session.isIdle(now)) {
        live.remove(key)
        session.close()
        persist(session)
      }
    }
  }

  fun flushAll() {
    live.values.forEach { persist(it) }
  }

  fun stats(
    userId: String,
    from: LocalDateTime,
    to: LocalDateTime,
  ): ReadingStats {
    flushAll()
    archiver.ensureUpToDate(userId)

    val fromDay = from.toLocalDate()
    val toDay = to.toLocalDate()
    val today = LocalDate.now()
    val archived = archiver.summaries(userId, fromDay, minOf(toDay, today.minusDays(1)))
    val liveFrom = if (archiver.enabled && from.isBefore(today.atStartOfDay())) today.atStartOfDay() else from
    val liveSessions =
      if (to.isBefore(liveFrom)) {
        emptyList()
      } else {
        readingSessionRepository.findAllByUserIdAndStartedAtBetween(userId, liveFrom, to)
      }

    val accumulator = StatsAccumulator()
    archived.forEach {
      accumulator.add(it.day, it.bookId, it.bookName, it.seriesId, it.libraryId, it.durationMs, it.pagesRead, it.sessionCount, it.lastReadAt)
    }
    val names = mutableMapOf<String, String>()
    liveSessions.forEach {
      val name = names.getOrPut(it.bookId) { bookRepository.findByIdOrNull(it.bookId)?.name.orEmpty() }
      accumulator.add(it.startedAt.toLocalDate(), it.bookId, name, it.seriesId, it.libraryId, it.durationMs, it.pagesRead, 1, it.lastHeartbeatAt)
    }

    val byBook =
      accumulator
        .books()
        .map {
          val book = bookRepository.findByIdOrNull(it.bookId)
          ReadingBookStats(
            bookId = it.bookId,
            bookName = book?.name ?: it.bookName.orEmpty(),
            seriesId = book?.seriesId ?: it.seriesId,
            libraryId = book?.libraryId ?: it.libraryId,
            pageCount = mediaRepository.findByIdOrNull(it.bookId)?.pageCount ?: 0,
            durationMs = it.durationMs,
            pagesRead = it.pagesRead,
            sessionCount = it.sessionCount,
            lastReadAt = it.lastReadAt?.toString().orEmpty(),
          )
        }.sortedByDescending { it.durationMs }

    val byDay =
      accumulator
        .days()
        .map {
          ReadingDailyStats(
            date = it.date.toString(),
            durationMs = it.durationMs,
            pagesRead = it.pagesRead,
            sessionCount = it.sessionCount,
          )
        }.sortedBy { it.date }

    return ReadingStats(
      from = from.toString(),
      to = to.toString(),
      totalDurationMs = accumulator.totalDurationMs,
      totalPagesRead = accumulator.totalPagesRead,
      sessionCount = accumulator.sessionCount,
      bookCount = byBook.size,
      books = byBook,
      days = byDay,
    )
  }

  fun bookStats(
    userId: String,
    bookId: String,
    from: LocalDateTime,
    to: LocalDateTime,
  ): BookReadingStats {
    flushAll()
    archiver.ensureUpToDate(userId)

    val fromDay = from.toLocalDate()
    val toDay = to.toLocalDate()
    val today = LocalDate.now()
    val archived =
      archiver
        .summaries(userId, fromDay, minOf(toDay, today.minusDays(1)))
        .filter { it.bookId == bookId }
    val liveFrom = if (archiver.enabled && from.isBefore(today.atStartOfDay())) today.atStartOfDay() else from
    val liveSessions =
      if (to.isBefore(liveFrom)) {
        emptyList()
      } else {
        readingSessionRepository.findAllByUserIdAndStartedAtBetween(userId, liveFrom, to).filter { it.bookId == bookId }
      }

    val days = mutableMapOf<LocalDate, DayAcc>()
    var duration = 0L
    var pages = 0
    var sessions = 0
    var lastReadAt: LocalDateTime? = null
    var archivedName = ""

    archived.forEach {
      val day = days.getOrPut(it.day) { DayAcc(it.day) }
      day.durationMs += it.durationMs
      day.pagesRead += it.pagesRead
      day.sessionCount += it.sessionCount
      duration += it.durationMs
      pages += it.pagesRead
      sessions += it.sessionCount
      if (it.bookName.isNotBlank()) archivedName = it.bookName
      if (it.lastReadAt != null && (lastReadAt == null || it.lastReadAt.isAfter(lastReadAt))) lastReadAt = it.lastReadAt
    }

    liveSessions.forEach {
      val date = it.startedAt.toLocalDate()
      val day = days.getOrPut(date) { DayAcc(date) }
      day.durationMs += it.durationMs
      day.pagesRead += it.pagesRead
      day.sessionCount += 1
      duration += it.durationMs
      pages += it.pagesRead
      sessions += 1
      if (lastReadAt == null || it.lastHeartbeatAt.isAfter(lastReadAt)) lastReadAt = it.lastHeartbeatAt
    }

    return BookReadingStats(
      bookId = bookId,
      bookName = bookRepository.findByIdOrNull(bookId)?.name ?: archivedName,
      from = fromDay.toString(),
      to = toDay.toString(),
      totalDurationMs = duration,
      totalPagesRead = pages,
      sessionCount = sessions,
      lastReadAt = lastReadAt?.toString().orEmpty(),
      days =
        days.values
          .map { ReadingDailyStats(it.date.toString(), it.durationMs, it.pagesRead, it.sessionCount) }
          .sortedBy { it.date },
    )
  }

  fun syncOfflineSessions(
    userId: String,
    deviceId: String,
    deviceName: String,
    inputs: List<OfflineSessionInput>,
  ): ReadingSyncResult {
    if (inputs.size > MAX_SYNC_BATCH) {
      return ReadingSyncResult(
        confirmed = emptyList(),
        duplicateCount = 0,
        rejected = listOf(ReadingSyncRejection(null, null, SYNC_REASON_TOO_MANY)),
      )
    }

    val now = LocalDateTime.now()
    val confirmed = mutableListOf<String>()
    val rejected = mutableListOf<ReadingSyncRejection>()
    val seen = mutableSetOf<String>()
    var duplicates = 0

    inputs.forEach { input ->
      val spanMs = Duration.between(input.startedAt, input.endedAt).toMillis()
      val reason =
        when {
          input.clientSessionId.isBlank() || input.clientSessionId.length > 64 -> SYNC_REASON_MISSING_ID
          !seen.add(input.clientSessionId) -> SYNC_REASON_DUPLICATE_IN_REQUEST
          !input.endedAt.isAfter(input.startedAt) -> SYNC_REASON_INVALID_RANGE
          input.durationMs <= 0 || input.durationMs > MAX_OFFLINE_SESSION_MS -> SYNC_REASON_DURATION_RANGE
          input.durationMs > (spanMs * (1.0 + MAX_DURATION_OVERSHOOT_RATIO)).toLong() -> SYNC_REASON_DURATION_MISMATCH
          input.endedAt.isAfter(now.plusMinutes(MAX_FUTURE_SKEW_MINUTES)) -> SYNC_REASON_FUTURE
          else -> null
        }

      if (reason != null) {
        rejected += ReadingSyncRejection(input.clientSessionId, input.bookId, reason)
        return@forEach
      }

      val book = bookRepository.findByIdOrNull(input.bookId)
      if (book == null) {
        rejected += ReadingSyncRejection(input.clientSessionId, input.bookId, SYNC_REASON_BOOK_NOT_FOUND)
        return@forEach
      }

      val inserted =
        readingSessionRepository.insertIfAbsent(
          ReadingSession(
            id = TsidCreator.getTsid256().toString(),
            userId = userId,
            bookId = input.bookId,
            seriesId = book.seriesId,
            libraryId = book.libraryId,
            startedAt = input.startedAt,
            lastHeartbeatAt = input.endedAt,
            endedAt = input.endedAt,
            durationMs = input.durationMs,
            startPage = input.startPage,
            lastPage = input.lastPage,
            pagesRead = if (input.pagesRead > 0) input.pagesRead else (input.lastPage - input.startPage).coerceAtLeast(0),
            deviceId = deviceId,
            deviceName = deviceName,
            client = input.client,
            clientSessionId = input.clientSessionId,
            syncedAt = now,
          ),
        )

      if (!inserted) duplicates++
      confirmed += input.clientSessionId
    }

    return ReadingSyncResult(confirmed = confirmed, duplicateCount = duplicates, rejected = rejected)
  }

  private fun persist(session: LiveSession) {
    runCatching { readingSessionRepository.upsert(session.toDomain()) }
      .onSuccess { session.markClean() }
      .onFailure { logger.warn(it) { "Failed to persist reading session ${session.id}" } }
  }

  private fun sessionKey(
    userId: String,
    deviceId: String,
    bookId: String,
  ) = "$userId|$deviceId|$bookId"
}

private class LiveSession(
  val id: String,
  val userId: String,
  val bookId: String,
  val seriesId: String,
  val libraryId: String,
  val deviceId: String,
  val deviceName: String,
  val client: String,
  val startedAt: LocalDateTime,
  val startPage: Int,
) {
  private var lastHeartbeatAt: LocalDateTime = startedAt
  private var endedAt: LocalDateTime? = null
  private var durationMs = 0L
  private var lastPage: Int = startPage
  private var pagesRead = 0
  private var dirty = true

  @Synchronized
  fun advance(
    now: LocalDateTime,
    page: Int,
  ) {
    durationMs += Duration.between(lastHeartbeatAt, now).toMillis().coerceIn(0, MAX_HEARTBEAT_GAP_MS)
    lastHeartbeatAt = now
    if (page > lastPage) lastPage = page
    pagesRead = (lastPage - startPage).coerceAtLeast(0)
    dirty = true
  }

  @Synchronized
  fun notePage(
    page: Int,
    now: LocalDateTime,
  ) {
    if (page > lastPage) {
      lastPage = page
      lastHeartbeatAt = now
    }
    pagesRead = (lastPage - startPage).coerceAtLeast(0)
    dirty = true
  }

  @Synchronized
  fun close() {
    endedAt = LocalDateTime.now()
    dirty = true
  }

  @Synchronized
  fun isIdle(now: LocalDateTime) = Duration.between(lastHeartbeatAt, now).toMinutes() >= IDLE_SESSION_TIMEOUT_MINUTES

  @Synchronized
  fun isDirty() = dirty

  @Synchronized
  fun markClean() {
    dirty = false
  }

  @Synchronized
  fun toDomain() =
    ReadingSession(
      id = id,
      userId = userId,
      bookId = bookId,
      seriesId = seriesId,
      libraryId = libraryId,
      startedAt = startedAt,
      lastHeartbeatAt = lastHeartbeatAt,
      endedAt = endedAt,
      durationMs = durationMs,
      startPage = startPage,
      lastPage = lastPage,
      pagesRead = pagesRead,
      deviceId = deviceId,
      deviceName = deviceName,
      client = client,
    )

  @Synchronized
  fun snapshot() =
    ReadingSessionSnapshot(
      id = id,
      bookId = bookId,
      startedAt = startedAt.toString(),
      lastHeartbeatAt = lastHeartbeatAt.toString(),
      endedAt = endedAt?.toString(),
      durationMs = durationMs,
      startPage = startPage,
      lastPage = lastPage,
      pagesRead = pagesRead,
      active = endedAt == null,
    )
}

data class ReadingSessionSnapshot(
  val id: String,
  val bookId: String,
  val startedAt: String,
  val lastHeartbeatAt: String,
  val endedAt: String? = null,
  val durationMs: Long = 0,
  val startPage: Int = 0,
  val lastPage: Int = 0,
  val pagesRead: Int = 0,
  val active: Boolean = true,
)

data class ReadingStats(
  val from: String,
  val to: String,
  val totalDurationMs: Long = 0,
  val totalPagesRead: Int = 0,
  val sessionCount: Int = 0,
  val bookCount: Int = 0,
  val books: List<ReadingBookStats> = emptyList(),
  val days: List<ReadingDailyStats> = emptyList(),
)

data class BookReadingStats(
  val bookId: String,
  val bookName: String = "",
  val from: String,
  val to: String,
  val totalDurationMs: Long = 0,
  val totalPagesRead: Int = 0,
  val sessionCount: Int = 0,
  val lastReadAt: String = "",
  val days: List<ReadingDailyStats> = emptyList(),
)

data class ReadingBookStats(
  val bookId: String,
  val bookName: String,
  val seriesId: String,
  val libraryId: String,
  val pageCount: Int,
  val durationMs: Long,
  val pagesRead: Int,
  val sessionCount: Int,
  val lastReadAt: String,
)

data class ReadingDailyStats(
  val date: String,
  val durationMs: Long,
  val pagesRead: Int,
  val sessionCount: Int,
)

data class OfflineSessionInput(
  val clientSessionId: String,
  val bookId: String,
  val startedAt: LocalDateTime,
  val endedAt: LocalDateTime,
  val durationMs: Long,
  val startPage: Int = 0,
  val lastPage: Int = 0,
  val pagesRead: Int = 0,
  val client: String = "",
)

data class ReadingSyncResult(
  val confirmed: List<String>,
  val duplicateCount: Int,
  val rejected: List<ReadingSyncRejection>,
)

data class ReadingSyncRejection(
  val clientSessionId: String?,
  val bookId: String?,
  val reason: String,
)

private class StatsAccumulator {
  private val books = mutableMapOf<String, BookAcc>()
  private val days = mutableMapOf<LocalDate, DayAcc>()

  var totalDurationMs: Long = 0
    private set
  var totalPagesRead: Int = 0
    private set
  var sessionCount: Int = 0
    private set

  fun add(
    day: LocalDate,
    bookId: String,
    bookName: String,
    seriesId: String,
    libraryId: String,
    durationMs: Long,
    pagesRead: Int,
    sessions: Int,
    lastReadAt: LocalDateTime?,
  ) {
    val book =
      books.getOrPut(bookId) {
        BookAcc(bookId)
      }
    if (bookName.isNotBlank()) book.bookName = bookName
    if (seriesId.isNotBlank()) book.seriesId = seriesId
    if (libraryId.isNotBlank()) book.libraryId = libraryId
    book.durationMs += durationMs
    book.pagesRead += pagesRead
    book.sessionCount += sessions
    if (lastReadAt != null && (book.lastReadAt == null || lastReadAt.isAfter(book.lastReadAt))) book.lastReadAt = lastReadAt

    val dayAcc =
      days.getOrPut(day) {
        DayAcc(day)
      }
    dayAcc.durationMs += durationMs
    dayAcc.pagesRead += pagesRead
    dayAcc.sessionCount += sessions

    totalDurationMs += durationMs
    totalPagesRead += pagesRead
    sessionCount += sessions
  }

  fun books(): Collection<BookAcc> = books.values

  fun days(): Collection<DayAcc> = days.values
}

private class BookAcc(
  val bookId: String,
) {
  var bookName: String? = null
  var seriesId: String = ""
  var libraryId: String = ""
  var durationMs: Long = 0
  var pagesRead: Int = 0
  var sessionCount: Int = 0
  var lastReadAt: LocalDateTime? = null
}

private class DayAcc(
  val date: LocalDate,
) {
  var durationMs: Long = 0
  var pagesRead: Int = 0
  var sessionCount: Int = 0
}
