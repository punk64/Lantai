package org.lantai.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.lantai.infrastructure.configuration.LantaiSettingsProvider
import org.lantai.infrastructure.runtime.LantaiMemoryReclaimer
import org.lantai.infrastructure.runtime.MemoryStatus
import org.lantai.infrastructure.runtime.ReclaimOutcome
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("api/lantai/v1/memory", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Lantai Memory")
class LantaiMemoryController(
  private val lantaiMemoryReclaimer: LantaiMemoryReclaimer,
  private val lantaiSettingsProvider: LantaiSettingsProvider,
) {
  @GetMapping("status")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Memory usage and reclaim state")
  fun status(): MemoryStatus = lantaiMemoryReclaimer.status()

  @PatchMapping("settings")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Update memory reclaim settings")
  fun updateSettings(
    @RequestBody settings: MemorySettingsUpdateDto,
  ): MemoryStatus {
    settings.enabled?.let { lantaiSettingsProvider.memoryReclaimEnabled = it }
    settings.idleMinutes?.let { lantaiSettingsProvider.memoryIdleMinutes = it.coerceIn(0, 1440) }
    settings.gcEnabled?.let { lantaiSettingsProvider.memoryGcEnabled = it }
    return lantaiMemoryReclaimer.status()
  }

  @PostMapping("reclaim")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Reclaim memory now")
  fun reclaim(): ReclaimOutcome =
    lantaiMemoryReclaimer.reclaim("manual", force = true)
      ?: throw ResponseStatusException(HttpStatus.CONFLICT, "reclaim-already-running")
}

data class MemorySettingsUpdateDto(
  val enabled: Boolean? = null,
  val idleMinutes: Int? = null,
  val gcEnabled: Boolean? = null,
)
