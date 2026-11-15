package org.lantai.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.lantai.domain.model.Library
import org.lantai.domain.persistence.LibraryRepository
import org.lantai.domain.service.FileSystemScanner
import org.lantai.domain.service.VolumeGroupingPreview
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Paths

@RestController
@RequestMapping("api/lantai/v1/libraries", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Lantai Library Volumes")
class LantaiLibraryVolumeController(
  private val libraryRepository: LibraryRepository,
  private val fileSystemScanner: FileSystemScanner,
) {
  @PostMapping("{libraryId}/volume-preview")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Dry run volume grouping: list the series and volumes that would be merged, without touching the database")
  fun preview(
    @PathVariable libraryId: String,
  ): VolumeGroupingPreview {
    val library = libraryRepository.findByIdOrNull(libraryId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    if (library.type == Library.Type.ONLINE) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "在线库不支持分卷预演")
    return fileSystemScanner.previewVolumeGrouping(
      Paths.get(library.root.toURI()),
      library.scanCbx,
      library.scanPdf,
      library.scanEpub,
      library.scanTxt,
      library.scanDirectoryExclusions,
    )
  }
}
