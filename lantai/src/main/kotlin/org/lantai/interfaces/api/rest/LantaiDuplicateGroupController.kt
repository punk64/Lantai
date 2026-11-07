package org.lantai.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.lantai.infrastructure.jooq.main.LantaiDuplicateGroupDao
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("api/lantai/v1/inspect", produces = [MediaType.APPLICATION_JSON_VALUE])
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Lantai Inspect")
class LantaiDuplicateGroupController(
  private val duplicateGroupDao: LantaiDuplicateGroupDao,
) {
  @GetMapping("duplicate-groups")
  @Operation(summary = "Paged groups of books sharing the same file hash")
  fun groups(
    @RequestParam(name = "page", required = false, defaultValue = "0") page: Int,
    @RequestParam(name = "size", required = false, defaultValue = "20") size: Int,
  ): Map<String, Any> = duplicateGroupDao.groups(page.coerceAtLeast(0), size.coerceIn(1, 100))
}
