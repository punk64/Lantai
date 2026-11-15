package org.lantai.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.lantai.infrastructure.jooq.main.LantaiLibraryStatsDao
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("api/lantai/v1/libraries", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Lantai Libraries")
class LantaiLibraryStatsController(
  private val lantaiLibraryStatsDao: LantaiLibraryStatsDao,
) {
  @GetMapping("stats")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Per-library series/book counts and total size")
  fun stats(): List<LantaiLibraryStatsDto> = lantaiLibraryStatsDao.stats().map {
    LantaiLibraryStatsDto(
      libraryId = it.libraryId,
      name = it.name,
      root = it.root,
      seriesCount = it.seriesCount,
      bookCount = it.bookCount,
      totalBytes = it.totalBytes,
    )
  }
}

data class LantaiLibraryStatsDto(
  val libraryId: String,
  val name: String,
  val root: String,
  val seriesCount: Int,
  val bookCount: Int,
  val totalBytes: Long,
)
