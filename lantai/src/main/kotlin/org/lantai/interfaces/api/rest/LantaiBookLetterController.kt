package org.lantai.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.lantai.domain.model.SearchContext
import org.lantai.infrastructure.jooq.main.LantaiBookLetterDao
import org.lantai.infrastructure.security.LantaiPrincipal
import org.lantai.interfaces.api.persistence.BookDtoRepository
import org.lantai.interfaces.api.rest.dto.BookDto
import org.lantai.interfaces.api.rest.dto.restrictUrl
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("api/lantai/v1/books", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Lantai Books")
class LantaiBookLetterController(
  private val lantaiBookLetterDao: LantaiBookLetterDao,
  private val bookDtoRepository: BookDtoRepository,
) {
  @GetMapping("letter-groups")
  @Operation(summary = "Book counts grouped by computed first letter")
  fun groups(
    @RequestParam(name = "libraryId", required = false) libraryId: String? = null,
  ): List<Map<String, Any>> = lantaiBookLetterDao.groups(libraryId)

  @GetMapping("letter-ids")
  @Operation(summary = "Book ids whose computed first letter matches")
  fun letterIds(
    @RequestParam(name = "letter") letter: String,
    @RequestParam(name = "libraryId", required = false) libraryId: String? = null,
  ): List<String> = lantaiBookLetterDao.idsByLetter(letter, libraryId)

  @GetMapping("letter-books")
  @Operation(summary = "Books whose computed first letter matches, paged")
  fun letterBooks(
    @AuthenticationPrincipal principal: LantaiPrincipal,
    @RequestParam(name = "letter") letter: String,
    @RequestParam(name = "libraryId", required = false) libraryId: String? = null,
    @RequestParam(name = "page", defaultValue = "0") page: Int,
    @RequestParam(name = "size", defaultValue = "50") size: Int,
    @RequestParam(name = "unpaged", required = false) unpaged: Boolean = false,
  ): Page<BookDto> {
    val context = SearchContext(principal.user, true)
    val ids = lantaiBookLetterDao.idsByLetter(letter, libraryId)
    if (unpaged) {
      val books = bookDtoRepository.findAllByIds(ids, context)
      val byId = books.associateBy { it.id }
      return PageImpl(ids.mapNotNull { byId[it] }, Pageable.unpaged(), ids.size.toLong())
    }
    val p = page.coerceAtLeast(0)
    val s = size.coerceIn(1, 500)
    val slice = ids.drop(p * s).take(s)
    val byId = bookDtoRepository.findAllByIds(slice, context).associateBy { it.id }
    val ordered = slice.mapNotNull { byId[it] }.map { it.restrictUrl(!principal.user.isAdmin) }
    return PageImpl(ordered, PageRequest.of(p, s), ids.size.toLong())
  }
}
