package org.lantai.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.lantai.infrastructure.jooq.main.LantaiReadingQueryDao
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDateTime

@RestController
@RequestMapping("api/lantai/v1", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Lantai Devices")
class LantaiDeviceController(
  private val lantaiReadingQueryDao: LantaiReadingQueryDao,
) {
  @GetMapping("devices")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Aggregate reading devices from session history")
  fun devices(): LantaiDeviceListDto {
    val rows = lantaiReadingQueryDao.devices()
    return LantaiDeviceListDto(
      total = rows.size,
      devices =
        rows.map {
          LantaiDeviceDto(
            deviceId = it.deviceId,
            deviceName = it.deviceName.orEmpty(),
            client = it.client.orEmpty(),
            sessionCount = it.sessionCount,
            durationMs = it.durationMs,
            pagesRead = it.pagesRead,
            bookCount = it.bookCount,
            lastActiveAt = it.lastActiveAt,
            firstSeenAt = it.firstSeenAt,
          )
        },
    )
  }

  @GetMapping("sessions")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "List reading sessions")
  fun sessions(
    @RequestParam(name = "deviceId", required = false) deviceId: String?,
    @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) from: LocalDateTime?,
    @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) to: LocalDateTime?,
    @RequestParam(name = "page", defaultValue = "0") page: Int,
    @RequestParam(name = "size", defaultValue = "50") size: Int,
  ): LantaiSessionPageDto {
    val pageSize = size.coerceIn(1, 200)
    val pageIndex = page.coerceAtLeast(0)
    val total = lantaiReadingQueryDao.countSessions(deviceId, from, to)
    val content =
      lantaiReadingQueryDao
        .sessions(deviceId, from, to, pageSize, pageIndex * pageSize)
        .map {
          LantaiSessionDto(
            id = it.id,
            userId = it.userId,
            bookId = it.bookId,
            seriesId = it.seriesId,
            libraryId = it.libraryId,
            startedAt = it.startedAt,
            lastHeartbeatAt = it.lastHeartbeatAt,
            endedAt = it.endedAt,
            durationMs = it.durationMs,
            startPage = it.startPage,
            lastPage = it.lastPage,
            pagesRead = it.pagesRead,
            deviceId = it.deviceId,
            deviceName = it.deviceName,
            client = it.client,
          )
        }
    return LantaiSessionPageDto(
      content = content,
      page = pageIndex,
      size = pageSize,
      totalElements = total.toLong(),
      totalPages = (total + pageSize - 1) / pageSize,
    )
  }
}

data class LantaiDeviceDto(
  val deviceId: String,
  val deviceName: String,
  val client: String,
  val sessionCount: Int,
  val durationMs: Long,
  val pagesRead: Int,
  val bookCount: Int,
  val lastActiveAt: LocalDateTime?,
  val firstSeenAt: LocalDateTime?,
)

data class LantaiDeviceListDto(
  val total: Int,
  val devices: List<LantaiDeviceDto>,
)

data class LantaiSessionDto(
  val id: String,
  val userId: String,
  val bookId: String,
  val seriesId: String,
  val libraryId: String,
  val startedAt: LocalDateTime,
  val lastHeartbeatAt: LocalDateTime,
  val endedAt: LocalDateTime?,
  val durationMs: Long,
  val startPage: Int,
  val lastPage: Int,
  val pagesRead: Int,
  val deviceId: String,
  val deviceName: String,
  val client: String,
)

data class LantaiSessionPageDto(
  val content: List<LantaiSessionDto>,
  val page: Int,
  val size: Int,
  val totalElements: Long,
  val totalPages: Int,
)
