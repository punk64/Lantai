package org.lantai.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.lantai.infrastructure.jooq.main.LantaiSeriesLetterDao
import org.lantai.infrastructure.i18n.SeriesFirstLetterBackfill
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("api/lantai/v1/series", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Lantai Series")
class LantaiSeriesLetterController(
  private val lantaiSeriesLetterDao: LantaiSeriesLetterDao,
  private val seriesFirstLetterBackfill: SeriesFirstLetterBackfill,
) {
  @GetMapping("letter-ids")
  @Operation(summary = "Series ids whose computed first letter matches")
  fun letterIds(
    @RequestParam(name = "letter") letter: String,
  ): List<String> = lantaiSeriesLetterDao.idsByLetter(letter)

  @PostMapping("letter-rebuild")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Recompute first letters for every series")
  fun rebuild(): Map<String, Int> = mapOf("updated" to seriesFirstLetterBackfill.run(force = true))
}
