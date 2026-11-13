package org.lantai.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.lantai.infrastructure.upgrade.CachedPackage
import org.lantai.infrastructure.upgrade.FetchRequest
import org.lantai.infrastructure.upgrade.RemoteInfo
import org.lantai.infrastructure.upgrade.RemoteProgress
import org.lantai.infrastructure.upgrade.RemoteUpgradeService
import org.lantai.infrastructure.upgrade.UpgradeInspect
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("api/lantai/v1/system/upgrade")
@Tag(name = "Lantai Upgrade")
class LantaiUpgradeRemoteController(
  private val remoteUpgradeService: RemoteUpgradeService,
) {
  @GetMapping(value = ["remote"], produces = [MediaType.APPLICATION_JSON_VALUE])
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Read the update feed and report the latest release plus any downloaded package")
  fun remote(
    @RequestParam(required = false) refresh: Boolean?,
  ): RemoteInfo = remoteUpgradeService.info(refresh == true)

  @GetMapping(value = ["remote/status"], produces = [MediaType.APPLICATION_JSON_VALUE])
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Progress of the package download currently running")
  fun remoteStatus(): RemoteProgress = remoteUpgradeService.progress()

  @GetMapping(value = ["cached"], produces = [MediaType.APPLICATION_JSON_VALUE])
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Packages already downloaded into the config directory")
  fun cached(): List<CachedPackage> = remoteUpgradeService.cached()

  @PostMapping(value = ["fetch"], produces = [MediaType.APPLICATION_JSON_VALUE])
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Download a release package, verify it and stage it for install")
  fun fetch(
    @RequestBody(required = false) request: FetchRequest?,
  ): UpgradeInspect = remoteUpgradeService.fetch(request ?: FetchRequest())

  @PostMapping(value = ["verify"], produces = [MediaType.APPLICATION_JSON_VALUE])
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Re-verify the most recent downloaded package")
  fun verify(): UpgradeInspect = remoteUpgradeService.verifyCached()

  @DeleteMapping(value = ["downloads"], produces = [MediaType.APPLICATION_JSON_VALUE])
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Delete every package downloaded from the update feed")
  fun clearDownloads(): List<CachedPackage> = remoteUpgradeService.clearDownloads()

  @ExceptionHandler(IllegalArgumentException::class, IllegalStateException::class)
  fun badPackage(e: Exception): ResponseEntity<Map<String, String>> =
    ResponseEntity.badRequest().body(mapOf("message" to (e.message ?: "升级失败")))
}
