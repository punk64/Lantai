package org.lantai.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import org.lantai.infrastructure.metadata.bulk.BulkMetadataService
import org.lantai.infrastructure.metadata.bulk.BulkResult
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("api/lantai/v1/bulk/metadata")
class LantaiBulkMetadataController(
  private val bulkMetadataService: BulkMetadataService,
) {
  @GetMapping("pending", produces = ["text/plain;charset=UTF-8"])
  @Operation(summary = "List entries awaiting bulk metadata")
  fun pending(
    @RequestParam(name = "limit", required = false, defaultValue = "50") limit: Int,
    @RequestParam(name = "offset", required = false, defaultValue = "0") offset: Int,
    @RequestParam(name = "onlyMissing", required = false, defaultValue = "true") onlyMissing: Boolean,
  ): String = bulkMetadataService.pendingSheet(limit.coerceIn(1, 200), offset.coerceAtLeast(0), onlyMissing)

  @GetMapping("count", produces = ["application/json"])
  @Operation(summary = "Count entries awaiting bulk metadata")
  fun count(
    @RequestParam(name = "onlyMissing", required = false, defaultValue = "true") onlyMissing: Boolean,
  ): Map<String, Int> = mapOf("pending" to bulkMetadataService.pendingCount(onlyMissing))

  @PostMapping("submit", consumes = ["text/plain"], produces = ["application/json"])
  @Operation(summary = "Submit bulk metadata for application")
  fun submit(
    @RequestParam(name = "dryRun", required = false, defaultValue = "true") dryRun: Boolean,
    @RequestBody text: String,
  ): BulkResult = bulkMetadataService.submit(text, dryRun)
}
