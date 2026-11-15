package org.lantai.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.lantai.infrastructure.jooq.main.LantaiSeriesVolumeDao
import org.lantai.infrastructure.security.LantaiPrincipal
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("api/lantai/v1", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Lantai Series Volumes")
class LantaiSeriesVolumeController(
  private val lantaiSeriesVolumeDao: LantaiSeriesVolumeDao,
) {
  data class VolumeItem(
    val name: String,
    val count: Int,
    val order: Int,
  )

  data class VolumesResponse(
    val seriesId: String,
    val total: Int,
    val bookTotal: Int,
    val sizeBytes: Long,
    val volumes: List<VolumeItem>,
  )

  data class NeighborsResponse(
    val bookId: String,
    val previousId: String?,
    val nextId: String?,
  )

  @GetMapping("series/{seriesId}/volumes")
  @Operation(summary = "Volumes of a series with book counts, ordered by number sort")
  fun volumes(
    @PathVariable seriesId: String,
  ): VolumesResponse {
    val rows = lantaiSeriesVolumeDao.volumes(seriesId)
    return VolumesResponse(
      seriesId = seriesId,
      total = rows.size,
      bookTotal = rows.sumOf { it.count },
      sizeBytes = lantaiSeriesVolumeDao.totalSize(seriesId),
      volumes = rows.map { VolumeItem(it.name, it.count, it.order) },
    )
  }

  @GetMapping("series/{seriesId}/continue")
  @Operation(summary = "Most recently read book of a series for the current user")
  fun continueBook(
    @AuthenticationPrincipal principal: LantaiPrincipal,
    @PathVariable seriesId: String,
  ): Map<String, String?> = mapOf("bookId" to lantaiSeriesVolumeDao.continueBookId(seriesId, principal.user.id))

  @GetMapping("books/{bookId}/neighbors")
  @Operation(summary = "Previous and next book of the same series in reading order")
  fun neighbors(
    @PathVariable bookId: String,
  ): NeighborsResponse {
    val row =
      lantaiSeriesVolumeDao.neighbors(bookId)
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    return NeighborsResponse(bookId, row.previousId, row.nextId)
  }
}
