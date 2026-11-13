package org.lantai.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.lantai.infrastructure.jooq.main.LantaiNavCountsDao
import org.lantai.infrastructure.jooq.main.NavCounts
import org.lantai.infrastructure.security.LantaiPrincipal
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("api/lantai/v1", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Lantai Counts")
class LantaiCountsController(
  private val lantaiNavCountsDao: LantaiNavCountsDao,
) {
  @GetMapping("counts")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "Sidebar counters in a single call")
  fun counts(
    @AuthenticationPrincipal principal: LantaiPrincipal,
  ): NavCounts = lantaiNavCountsDao.counts(principal.user.id)
}
