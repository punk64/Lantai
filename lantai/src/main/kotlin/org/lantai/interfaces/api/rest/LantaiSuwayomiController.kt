package org.lantai.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.lantai.infrastructure.online.OnlineException
import org.lantai.infrastructure.online.SuwayomiAdminService
import org.lantai.infrastructure.online.SuwayomiActionResult
import org.lantai.infrastructure.online.SuwayomiCatalogDto
import org.lantai.infrastructure.online.SuwayomiCatalogPage
import org.lantai.infrastructure.online.SuwayomiException
import org.lantai.infrastructure.online.SuwayomiExtensionDto
import org.lantai.infrastructure.online.SuwayomiExtensionSourceDto
import org.lantai.infrastructure.online.SuwayomiExtensionStore
import org.lantai.infrastructure.online.SuwayomiReleaseDto
import org.lantai.infrastructure.online.SuwayomiStatusDto
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("api/lantai/v1/suwayomi", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Lantai Suwayomi")
class LantaiSuwayomiController(
  private val adminService: SuwayomiAdminService,
) {
  @GetMapping("status")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Suwayomi runtime status")
  fun status(): SuwayomiStatusDto = call { adminService.status() }

  @GetMapping("settings")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Read Suwayomi settings")
  fun settings(): Map<String, Any?> = call { adminService.settings() }

  @PatchMapping("settings")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Update Suwayomi settings")
  fun updateSettings(
    @RequestBody values: Map<String, Any>,
  ): Map<String, Any?> = call { adminService.updateSettings(values) }

  @PostMapping("lifecycle/{action}")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Start, stop or restart Suwayomi")
  fun lifecycle(
    @PathVariable action: String,
  ): SuwayomiStatusDto =
    call {
      when (action) {
        "start" -> adminService.start()
        "stop" -> adminService.stop()
        "restart" -> adminService.restart()
        else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown action $action")
      }
    }

  @GetMapping("extensions")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "List installed extensions")
  fun extensions(): List<SuwayomiExtensionDto> = call { adminService.listExtensions() }

  @GetMapping("extensions/catalog")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Browse installable extensions")
  fun catalog(
    @RequestParam(name = "q", required = false) keyword: String?,
    @RequestParam(name = "lang", required = false) lang: String?,
    @RequestParam(name = "onlyInstalled", defaultValue = "false") onlyInstalled: Boolean,
    @RequestParam(name = "offset", defaultValue = "0") offset: Int,
    @RequestParam(name = "limit", defaultValue = "60") limit: Int,
    @RequestParam(name = "force", defaultValue = "false") force: Boolean,
  ): SuwayomiCatalogPage = call { adminService.catalog(keyword, lang, onlyInstalled, offset, limit, force) }

  @PostMapping("extensions/fetch")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Refresh installed extension metadata")
  fun fetchExtensions(): SuwayomiActionResult = call { adminService.fetchExtensions() }

  @GetMapping("extensions/{pkgName}/sources")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "List the sources provided by an extension")
  fun extensionSources(
    @PathVariable pkgName: String,
  ): List<SuwayomiExtensionSourceDto> = call { adminService.extensionSources(pkgName) }

  @PostMapping("extensions/{pkgName}/install")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Install an extension from the catalog")
  fun installExtension(
    @PathVariable pkgName: String,
  ): SuwayomiActionResult = call { adminService.installExtension(pkgName) }

  @PostMapping("extensions/upload", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Install an extension from an uploaded package")
  fun uploadExtensionPackage(
    @RequestPart("file") file: MultipartFile,
  ): SuwayomiActionResult = call { adminService.installExtensionPackage(file.bytes, file.originalFilename) }

  @PostMapping("extensions/{pkgName}/update")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Update an installed extension")
  fun updateExtension(
    @PathVariable pkgName: String,
  ): SuwayomiActionResult = call { adminService.updateExtension(pkgName) }

  @DeleteMapping("extensions/{pkgName}")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Uninstall an extension")
  fun uninstallExtension(
    @PathVariable pkgName: String,
  ): SuwayomiActionResult = call { adminService.uninstallExtension(pkgName) }

  @GetMapping("stores")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "List extension stores")
  fun stores(): List<SuwayomiExtensionStore> = call { adminService.stores() }

  @PostMapping("stores")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Add an extension store")
  fun addStore(
    @RequestBody request: SuwayomiStoreRequest,
  ): SuwayomiActionResult = call { adminService.addStore(request.indexUrl) }

  @GetMapping("release")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Latest Suwayomi release")
  fun release(): SuwayomiReleaseDto = call { adminService.latestRelease() }

  @PostMapping("release/install")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Download and install the latest Suwayomi release")
  fun installLatest(): SuwayomiStatusDto = call { adminService.installLatest() }

  @PostMapping("jar", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Import a Suwayomi jar manually")
  fun importJar(
    @RequestParam("file") file: MultipartFile,
  ): SuwayomiStatusDto = call { adminService.importJar(file.bytes) }

  @PostMapping("password/rotate")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Rotate the managed Suwayomi credentials")
  fun rotatePassword(): SuwayomiActionResult = call { adminService.rotatePassword() }

  private fun <T> call(block: () -> T): T =
    try {
      block()
    } catch (e: OnlineException) {
      throw ResponseStatusException(HttpStatus.BAD_GATEWAY, e.message, e)
    } catch (e: SuwayomiException) {
      throw ResponseStatusException(HttpStatus.BAD_GATEWAY, e.message, e)
    } catch (e: IllegalArgumentException) {
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
    }
}

data class SuwayomiStoreRequest(
  val indexUrl: String,
)
