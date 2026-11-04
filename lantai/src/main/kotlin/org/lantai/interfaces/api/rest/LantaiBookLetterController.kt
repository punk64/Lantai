package org.lantai.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.lantai.infrastructure.jooq.main.LantaiBookLetterDao
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("api/lantai/v1/books", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Lantai Books")
class LantaiBookLetterController(
  private val lantaiBookLetterDao: LantaiBookLetterDao,
) {
  @GetMapping("letter-groups")
  @Operation(summary = "Book counts grouped by computed first letter")
  fun groups(): List<Map<String, Any>> = lantaiBookLetterDao.groups()

  @GetMapping("letter-ids")
  @Operation(summary = "Book ids whose computed first letter matches")
  fun letterIds(
    @RequestParam(name = "letter") letter: String,
  ): List<String> = lantaiBookLetterDao.idsByLetter(letter)
}
