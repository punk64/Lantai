package org.lantai.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import org.lantai.infrastructure.metadata.bulk.BulkMetadataService
import org.lantai.infrastructure.metadata.bulk.BulkResult
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("api/lantai/v1/series/metadata")
@PreAuthorize("hasRole('ADMIN')")
class LantaiMetadataAdminController(
  private val bulkMetadataService: BulkMetadataService,
) {
  @PostMapping("export", produces = ["text/plain;charset=UTF-8"])
  @Operation(summary = "Export series metadata as a text file")
  fun export(
    @RequestBody body: BulkExportRequest,
  ): String = bulkMetadataService.sheet(body.seriesIds)

  @PostMapping("bulk", consumes = ["text/plain"], produces = ["application/json"])
  @Operation(summary = "Apply bulk series metadata from a text payload")
  fun bulk(
    @RequestParam(name = "dryRun", required = false, defaultValue = "true") dryRun: Boolean,
    @RequestBody text: String,
  ): BulkResult = bulkMetadataService.submit(text, dryRun)
}

data class BulkExportRequest(
  val seriesIds: List<String> = emptyList(),
)
