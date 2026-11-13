package org.lantai.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.lantai.infrastructure.upgrade.UpgradeAccepted
import org.lantai.infrastructure.upgrade.UpgradeApply
import org.lantai.infrastructure.upgrade.UpgradeProgress
import org.lantai.infrastructure.upgrade.UpgradeRestart
import org.lantai.infrastructure.upgrade.UpgradeService
import org.lantai.infrastructure.upgrade.UpgradeStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("api/lantai/v1/system/upgrade")
@Tag(name = "Lantai Upgrade")
class LantaiUpgradeController(
  private val upgradeService: UpgradeService,
) {
  @GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Current jar location and whether it can be replaced in place")
  fun status(): UpgradeStatus = upgradeService.status()

  @PostMapping(value = ["inspect"], consumes = [MediaType.MULTIPART_FORM_DATA_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Upload an upgrade package; validation runs in the background")
  fun inspect(
    @RequestPart("file") file: MultipartFile,
  ): UpgradeAccepted = upgradeService.inspect(file)

  @GetMapping(value = ["inspect/status"], produces = [MediaType.APPLICATION_JSON_VALUE])
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Progress of the package validation currently running")
  fun inspectStatus(): UpgradeProgress = upgradeService.progress()

  @PostMapping(value = ["apply"], produces = [MediaType.APPLICATION_JSON_VALUE])
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Replace the running jar with the staged one and restart")
  fun apply(): UpgradeApply = upgradeService.apply()

  @PostMapping(value = ["rollback"], produces = [MediaType.APPLICATION_JSON_VALUE])
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Restore the last backup jar and restart")
  fun rollback(): UpgradeApply = upgradeService.rollback()

  @PostMapping(value = ["restart"], produces = [MediaType.APPLICATION_JSON_VALUE])
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Restart the server without changing the program files")
  fun restart(): UpgradeRestart = upgradeService.restart()

  @DeleteMapping(value = ["staged"], produces = [MediaType.APPLICATION_JSON_VALUE])
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Discard the staged package")
  fun discard(): Map<String, Boolean> {
    upgradeService.discard()
    return mapOf("discarded" to true)
  }

  @ExceptionHandler(IllegalArgumentException::class, IllegalStateException::class)
  fun badPackage(e: Exception): ResponseEntity<Map<String, String>> =
    ResponseEntity.badRequest().body(mapOf("message" to (e.message ?: "升级包不合法")))
}
