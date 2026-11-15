package org.lantai.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.lantai.infrastructure.jooq.main.LantaiCatalogFacetDao
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("api/lantai/v1/catalog", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Lantai Catalog")
class LantaiCatalogFacetController(
  private val facetDao: LantaiCatalogFacetDao,
) {
  @GetMapping("facets")
  @Operation(summary = "Distinct filter values and counts for the catalog")
  fun facets(
    @RequestParam(name = "libraryId", required = false) libraryId: String? = null,
  ): Map<String, Any> = facetDao.facets(libraryId)
}
