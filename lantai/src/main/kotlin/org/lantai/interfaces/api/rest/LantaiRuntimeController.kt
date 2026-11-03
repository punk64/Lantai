package org.lantai.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.lantai.infrastructure.runtime.RuntimeRegistry
import org.lantai.infrastructure.runtime.RuntimeState
import org.lantai.infrastructure.runtime.install.AutoInstallSnapshot
import org.lantai.infrastructure.runtime.install.RuntimeAutoInstaller
import org.lantai.infrastructure.runtime.install.RuntimeDetection
import org.lantai.infrastructure.runtime.install.RuntimeInstallRegistry
import org.lantai.infrastructure.runtime.install.RuntimeInstallRunner
import org.lantai.infrastructure.runtime.install.RuntimePackageInfo
import org.lantai.infrastructure.runtime.install.RuntimeProgress
import org.lantai.infrastructure.runtime.install.RuntimeStep
import org.lantai.infrastructure.runtime.install.RuntimeToggleResult
import org.lantai.infrastructure.runtime.install.RuntimeUninstallResult
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("api/lantai/v1/runtimes", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Lantai Runtime")
class LantaiRuntimeController(
  private val registry: RuntimeRegistry,
  private val installs: RuntimeInstallRegistry,
  private val runner: RuntimeInstallRunner,
  private val autoInstaller: RuntimeAutoInstaller,
) {
  @GetMapping
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Probe every external runtime managed by Lantai")
  fun list(): List<RuntimeState> = registry.states()

  @GetMapping("{kind}")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Probe one external runtime")
  fun get(
    @PathVariable kind: String,
  ): RuntimeState = registry.state(kind) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

  @PostMapping("{kind}/start")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Start an external runtime")
  fun start(
    @PathVariable kind: String,
  ): RuntimeState = registry.get(kind)?.start() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

  @PostMapping("{kind}/stop")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Stop an external runtime")
  fun stop(
    @PathVariable kind: String,
  ): RuntimeState = registry.get(kind)?.stop() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

  @GetMapping("{kind}/detect")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Environment and dependency check for an external runtime")
  fun detect(
    @PathVariable kind: String,
  ): RuntimeDetection = installs.detection(kind) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

  @GetMapping("{kind}/remote")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Available versions and download sources")
  fun remote(
    @PathVariable kind: String,
  ): RuntimePackageInfo = installs.get(kind)?.describe() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

  @GetMapping("{kind}/progress")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Current install progress")
  fun progress(
    @PathVariable kind: String,
  ): RuntimeProgress = installs.get(kind)?.progress() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

  @GetMapping("{kind}/script")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Equivalent shell steps for a manual install")
  fun script(
    @PathVariable kind: String,
  ): List<RuntimeStep> = installs.get(kind)?.steps() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

  @PostMapping("{kind}/pull")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Download from the official source and install in the background")
  fun pull(
    @PathVariable kind: String,
  ): ResponseEntity<RuntimeProgress> {
    val installer = installs.get(kind) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    val accepted = runner.submit(kind) { installer.pull() }
    val status = if (accepted) HttpStatus.ACCEPTED else HttpStatus.CONFLICT
    val message = if (accepted) "install started" else "an install is already running for $kind"
    return ResponseEntity.status(status).body(installer.progress().copy(message = message))
  }

  @PostMapping("{kind}/accept", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Upload a package and install it in the background")
  fun accept(
    @PathVariable kind: String,
    @RequestPart("file") file: MultipartFile,
    @RequestParam(name = "install", defaultValue = "true") install: Boolean,
  ): ResponseEntity<RuntimePackageInfo> {
    val installer = installs.get(kind) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    val packageFile = installer.accept(file.bytes, file.originalFilename)
    if (!install) return ResponseEntity.ok(packageFile.info)
    val accepted = runner.submit(kind) { installer.install(packageFile) }
    val status = if (accepted) HttpStatus.ACCEPTED else HttpStatus.CONFLICT
    return ResponseEntity.status(status).body(packageFile.info)
  }

  @DeleteMapping("{kind}")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Uninstall a runtime, data is removed only when purge is requested")
  fun uninstall(
    @PathVariable kind: String,
    @RequestParam(name = "purgeData", defaultValue = "false") purgeData: Boolean,
  ): RuntimeUninstallResult =
    installs.get(kind)?.uninstall(purgeData) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

  @PutMapping("{kind}/enabled")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Enable or disable a runtime")
  fun setEnabled(
    @PathVariable kind: String,
    @RequestBody body: RuntimeToggleDto,
  ): RuntimeToggleResult =
    installs.toggle(kind)?.setEnabled(body.enabled) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

  @GetMapping("install/auto")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Snapshot of the automatic install performed at startup")
  fun autoInstall(): AutoInstallSnapshot = autoInstaller.snapshot()

  @PostMapping("install/auto")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Run the automatic install for the given runtimes")
  fun triggerAutoInstall(
    @RequestBody body: RuntimeAutoInstallDto,
  ): AutoInstallSnapshot = autoInstaller.trigger(body.kinds)
}

data class RuntimeToggleDto(
  val enabled: Boolean,
)

data class RuntimeAutoInstallDto(
  val kinds: List<String>,
)
