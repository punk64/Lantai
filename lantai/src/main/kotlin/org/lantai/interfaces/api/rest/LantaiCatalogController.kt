package org.lantai.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.lantai.infrastructure.jooq.main.LantaiCatalogDao
import org.lantai.infrastructure.jooq.main.ReadyCatalog
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("api/lantai/v1/catalog", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Lantai Catalog")
class LantaiCatalogController(
  private val catalogDao: LantaiCatalogDao,
) {
  @GetMapping("ready")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "Series ids with at least one analyzed book, plus the analyzed book count")
  fun ready(): ReadyCatalog = catalogDao.readyCatalog()
}
