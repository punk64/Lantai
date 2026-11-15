package org.lantai.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

private const val LANTAI_BUILD_MARKER = "step1-initial"

@RestController
@RequestMapping("api/lantai/v1", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Lantai")
class LantaiController {
  @GetMapping("echo")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Echo build and runtime information")
  fun echo(): LantaiEchoDto =
    LantaiEchoDto(
      name = "lantai",
      status = "ok",
      marker = LANTAI_BUILD_MARKER,
      loadedAt = Instant.now().toString(),
    )
}

data class LantaiEchoDto(
  val name: String,
  val status: String,
  val marker: String,
  val loadedAt: String,
)
