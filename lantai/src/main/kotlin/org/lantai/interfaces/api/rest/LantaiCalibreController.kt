package org.lantai.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.lantai.infrastructure.calibre.CalibreBatchConversion
import org.lantai.infrastructure.calibre.CalibreBookConversion
import org.lantai.infrastructure.calibre.CalibreBookConverter
import org.lantai.infrastructure.calibre.CalibreInstaller
import org.lantai.infrastructure.calibre.CalibreRelease
import org.lantai.infrastructure.calibre.CalibreRuntime
import org.lantai.infrastructure.calibre.CalibreStatus
import org.lantai.infrastructure.configuration.LantaiSettingsProvider
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Paths

@RestController
@RequestMapping("api/lantai/v1/calibre", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Lantai Calibre")
class LantaiCalibreController(
  private val runtime: CalibreRuntime,
  private val installer: CalibreInstaller,
  private val bookConverter: CalibreBookConverter,
  private val settingsProvider: LantaiSettingsProvider,
) {
  @GetMapping("status")
  @Operation(summary = "Calibre availability, version and conversion mode")
  @PreAuthorize("hasRole('ADMIN')")
  fun status(): CalibreStatus = runtime.status()

  @GetMapping("release")
  @Operation(summary = "Latest Calibre portable release")
  @PreAuthorize("hasRole('ADMIN')")
  fun release(): CalibreRelease = installer.latestRelease()

  @PostMapping("install")
  @Operation(summary = "Download and install the latest Calibre portable build")
  @PreAuthorize("hasRole('ADMIN')")
  fun install(): CalibreStatus {
    val staged = installer.downloadInstaller()
    return installer.install(staged)
  }

  @PostMapping("import", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
  @Operation(summary = "Import a locally downloaded Calibre portable installer")
  @PreAuthorize("hasRole('ADMIN')")
  fun import(
    @RequestPart("file") file: MultipartFile,
    @RequestParam(name = "install", defaultValue = "true") install: Boolean,
  ): CalibreStatus {
    val staged = installer.importInstaller(file.bytes, file.originalFilename)
    return if (install) installer.install(staged) else runtime.status()
  }

  @PostMapping("probe")
  @Operation(summary = "Probe an ebook-convert executable without saving it")
  @PreAuthorize("hasRole('ADMIN')")
  fun probe(
    @RequestBody body: CalibreProbeDto,
  ): CalibreProbeResult {
    val path = Paths.get(body.path).toAbsolutePath().normalize()
    val version = runtime.probeVersion(path)
    return CalibreProbeResult(path = path.toString(), available = version != null, version = version)
  }

  @PutMapping("path")
  @Operation(summary = "Pin an existing ebook-convert executable")
  @PreAuthorize("hasRole('ADMIN')")
  fun setPath(
    @RequestBody body: CalibreProbeDto,
  ): CalibreStatus {
    val path = Paths.get(body.path).toAbsolutePath().normalize()
    if (runtime.probeVersion(path) == null) {
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, "No working ebook-convert at $path")
    }
    settingsProvider.calibreExecutablePath = path.toString()
    return runtime.status()
  }

  @DeleteMapping("path")
  @Operation(summary = "Clear the pinned executable and fall back to the managed installation")
  @PreAuthorize("hasRole('ADMIN')")
  fun clearPath(): CalibreStatus {
    settingsProvider.calibreExecutablePath = ""
    return runtime.status()
  }

  @PutMapping("mode")
  @Operation(summary = "Set the conversion mode: never, on-demand or eager")
  @PreAuthorize("hasRole('ADMIN')")
  fun setMode(
    @RequestBody body: CalibreModeDto,
  ): CalibreStatus {
    if (body.mode !in CalibreRuntime.MODES) {
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown conversion mode: ${body.mode}")
    }
    settingsProvider.calibreConvertMode = body.mode
    return runtime.status()
  }

  @GetMapping("pending")
  @Operation(summary = "Count books still waiting for conversion in a library")
  @PreAuthorize("hasRole('ADMIN')")
  fun pending(
    @RequestParam libraryId: String,
  ): CalibrePendingResult = CalibrePendingResult(libraryId = libraryId, pending = bookConverter.pendingCount(libraryId))

  @PostMapping("convert")
  @Operation(summary = "Convert a single book and queue re-analysis")
  @PreAuthorize("hasRole('ADMIN')")
  fun convert(
    @RequestBody body: CalibreConvertDto,
  ): CalibreBookConversion = bookConverter.convertBook(body.bookId)

  @PostMapping("convert/batch")
  @Operation(summary = "Convert pending books of a library")
  @PreAuthorize("hasRole('ADMIN')")
  fun convertBatch(
    @RequestBody body: CalibreBatchConvertDto,
  ): CalibreBatchConversion = bookConverter.convertLibrary(body.libraryId, body.limit)
}

data class CalibreProbeDto(
  val path: String,
)

data class CalibreProbeResult(
  val path: String,
  val available: Boolean,
  val version: String?,
)

data class CalibreModeDto(
  val mode: String,
)

data class CalibrePendingResult(
  val libraryId: String,
  val pending: Int,
)

data class CalibreConvertDto(
  val bookId: String,
)

data class CalibreBatchConvertDto(
  val libraryId: String,
  val limit: Int? = null,
)
