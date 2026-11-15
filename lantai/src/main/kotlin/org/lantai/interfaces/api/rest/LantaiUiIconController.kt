package org.lantai.interfaces.api.rest

import com.fasterxml.jackson.databind.JsonNode
import io.swagger.v3.oas.annotations.Operation
import org.lantai.infrastructure.lantai.UiIconService
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("api/lantai/v1/ui")
class LantaiUiIconController(
  private val icons: UiIconService,
) {
  @GetMapping("icons", produces = [MediaType.APPLICATION_JSON_VALUE])
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "Read menu icon overrides configured for the host web UI")
  fun list(): Map<String, JsonNode> = icons.icons()

  @GetMapping("icon", produces = [MediaType.ALL_VALUE])
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "Read an icon file from the host ui directory")
  fun icon(
    @RequestParam(name = "path") path: String,
  ): ResponseEntity<ByteArray> {
    val file = icons.read(path) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "图标文件不存在")
    return ResponseEntity
      .ok()
      .contentType(MediaType.parseMediaType(file.mediaType))
      .eTag(file.etag)
      .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'")
      .header("X-Content-Type-Options", "nosniff")
      .body(file.bytes)
  }
}
