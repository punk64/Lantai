package org.lantai.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.lantai.domain.service.BookReadingStats
import org.lantai.domain.service.OfflineSessionInput
import org.lantai.domain.service.ReadingArchiveStatus
import org.lantai.domain.service.ReadingSessionSnapshot
import org.lantai.domain.service.ReadingStats
import org.lantai.domain.service.ReadingStatsArchiver
import org.lantai.domain.service.ReadingStatsRecorder
import org.lantai.domain.service.ReadingSyncResult
import org.lantai.infrastructure.read.IncognitoRequestGuard
import org.lantai.infrastructure.security.LantaiPrincipal
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

@RestController
@RequestMapping("api/lantai/v1/reading", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Lantai Reading")
class LantaiReadingController(
  private val readingStatsRecorder: ReadingStatsRecorder,
  private val readingStatsArchiver: ReadingStatsArchiver,
  private val incognitoRequestGuard: IncognitoRequestGuard,
) {
  @PostMapping("heartbeat")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "Report a reading heartbeat")
  fun heartbeat(
    @AuthenticationPrincipal principal: LantaiPrincipal,
    @RequestBody dto: ReadingHeartbeatDto,
  ): ReadingSessionSnapshot? {
    if (incognitoRequestGuard.isOn()) return null

    return readingStatsRecorder.heartbeat(
      userId = principal.user.id,
      bookId = dto.bookId,
      page = dto.page,
      deviceId = dto.deviceId.orEmpty(),
      deviceName = dto.deviceName.orEmpty(),
      client = dto.client.orEmpty(),
    )
  }

  @PostMapping("stop")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "Stop the current reading session")
  fun stop(
    @AuthenticationPrincipal principal: LantaiPrincipal,
    @RequestBody dto: ReadingStopDto,
  ): ReadingSessionSnapshot? =
    if (incognitoRequestGuard.isOn()) null
    else
      readingStatsRecorder.stop(
        userId = principal.user.id,
        bookId = dto.bookId,
        deviceId = dto.deviceId.orEmpty(),
      )

  @PostMapping("sync")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "Sync offline reading sessions")
  fun sync(
    @AuthenticationPrincipal principal: LantaiPrincipal,
    @RequestBody dto: ReadingSyncDto,
  ): ReadingSyncResult {
    if (dto.device.id.isBlank()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "device.id is required")

    return readingStatsRecorder.syncOfflineSessions(
      userId = principal.user.id,
      deviceId = dto.device.id,
      deviceName = dto.device.name,
      inputs = dto.sessions.map { it.toInput() },
    )
  }

  @GetMapping("capabilities")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "Get Lantai reading capabilities")
  fun capabilities(): LantaiReadingCapabilitiesDto = LantaiReadingCapabilitiesDto()

  @GetMapping("active")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "List active reading sessions for the current user")
  fun active(
    @AuthenticationPrincipal principal: LantaiPrincipal,
  ): List<ReadingSessionSnapshot> = readingStatsRecorder.activeSessions(principal.user.id)

  @GetMapping("stats")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "Get reading statistics for the current user")
  fun stats(
    @AuthenticationPrincipal principal: LantaiPrincipal,
    @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
    @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
  ): ReadingStats {
    val end = (to ?: LocalDate.now()).plusDays(1).atStartOfDay().minusNanos(1)
    val start = (from ?: end.toLocalDate().minusDays(29)).atStartOfDay()
    return readingStatsRecorder.stats(principal.user.id, start, end)
  }

  @GetMapping("books/{bookId}")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "Get the reading time of one book for the current user")
  fun bookStats(
    @AuthenticationPrincipal principal: LantaiPrincipal,
    @PathVariable bookId: String,
    @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
    @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
  ): BookReadingStats {
    val end = (to ?: LocalDate.now()).plusDays(1).atStartOfDay().minusNanos(1)
    val start = (from ?: end.toLocalDate().minusDays(29)).atStartOfDay()
    return readingStatsRecorder.bookStats(principal.user.id, bookId, start, end)
  }

  @GetMapping("archive")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "Get the archive status of the current user's reading statistics")
  fun archive(
    @AuthenticationPrincipal principal: LantaiPrincipal,
  ): ReadingArchiveStatus = readingStatsArchiver.status(principal.user.id)

  @PostMapping("archive/rebuild")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "Rebuild the daily reading statistics of the current user")
  fun rebuildArchive(
    @AuthenticationPrincipal principal: LantaiPrincipal,
  ): Map<String, Int> = mapOf("rows" to readingStatsArchiver.reconcile(principal.user.id))

  @PostMapping("archive/purge")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Purge reading sessions beyond the retention window")
  fun purgeArchive(): Map<String, Int> = mapOf("deleted" to readingStatsArchiver.purge())
}

data class ReadingHeartbeatDto(
  val bookId: String,
  val page: Int = 1,
  val deviceId: String? = null,
  val deviceName: String? = null,
  val client: String? = null,
)

data class ReadingStopDto(
  val bookId: String,
  val deviceId: String? = null,
)

data class ReadingSyncDto(
  val device: ReadingSyncDeviceDto,
  val sessions: List<ReadingSyncSessionDto> = emptyList(),
)

data class ReadingSyncDeviceDto(
  val id: String = "",
  val name: String = "",
)

data class ReadingSyncSessionDto(
  val clientSessionId: String = "",
  val bookId: String = "",
  val startedAt: ZonedDateTime,
  val endedAt: ZonedDateTime,
  val durationMs: Long = 0,
  val startPage: Int = 0,
  val lastPage: Int = 0,
  val pagesRead: Int = 0,
  val client: String = "",
) {
  fun toInput(): OfflineSessionInput =
    OfflineSessionInput(
      clientSessionId = clientSessionId,
      bookId = bookId,
      startedAt = startedAt.withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime(),
      endedAt = endedAt.withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime(),
      durationMs = durationMs,
      startPage = startPage,
      lastPage = lastPage,
      pagesRead = pagesRead,
      client = client,
    )
}

data class LantaiReadingCapabilitiesDto(
  val heartbeat: Boolean = true,
  val offlineSync: Boolean = true,
  val apiVersion: Int = 1,
)
