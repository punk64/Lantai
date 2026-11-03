package org.lantai.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.lantai.infrastructure.metadata.scrape.ScrapeSettings
import org.lantai.infrastructure.metadata.scrape.ScrapeSettingsProvider
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("api/lantai/v1/scrape/settings", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Lantai Scrape Settings")
class LantaiScrapeSettingsController(
  private val settings: ScrapeSettingsProvider,
) {
  @GetMapping
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Read scrape settings")
  fun read(): ScrapeSettings = settings.snapshot()

  @PatchMapping
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Update scrape settings, trusting the results requires an explicit confirmation")
  fun update(
    @RequestBody body: ScrapeSettingsUpdate,
  ): ScrapeSettings {
    if (body.trustResult == true && !body.confirm) {
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, "开启「信任刮削结果」需要显式确认")
    }
    body.trustResult?.let { settings.trustResult = it }
    body.batchSize?.let { settings.batchSize = it }
    return settings.snapshot()
  }
}

data class ScrapeSettingsUpdate(
  val trustResult: Boolean? = null,
  val batchSize: Int? = null,
  val confirm: Boolean = false,
)
