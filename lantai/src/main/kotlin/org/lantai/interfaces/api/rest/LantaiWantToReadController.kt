package org.lantai.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.lantai.domain.model.SearchContext
import org.lantai.domain.model.WantToRead
import org.lantai.domain.persistence.BookRepository
import org.lantai.domain.persistence.ReadProgressRepository
import org.lantai.domain.persistence.WantToReadRepository
import org.lantai.infrastructure.security.LantaiPrincipal
import org.lantai.interfaces.api.persistence.BookDtoRepository
import org.lantai.interfaces.api.rest.dto.BookDto
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("api/lantai/v1/want-to-read")
@Tag(name = "Lantai Want To Read")
class LantaiWantToReadController(
  private val wantToReadRepository: WantToReadRepository,
  private val bookDtoRepository: BookDtoRepository,
  private val bookRepository: BookRepository,
  private val readProgressRepository: ReadProgressRepository,
) {
  @GetMapping
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "List the current user want-to-read books")
  fun list(
    @AuthenticationPrincipal principal: LantaiPrincipal,
    @RequestParam(name = "page", required = false, defaultValue = "0") page: Int,
    @RequestParam(name = "size", required = false, defaultValue = "16") size: Int,
  ): WantToReadPageDto {
    val safePage = page.coerceAtLeast(0)
    val safeSize = size.coerceIn(1, 50)
    val ids = wantToReadRepository.findBookIdsByUserId(principal.user.id, safeSize, safePage * safeSize)
    val items =
      if (ids.isEmpty()) {
        emptyList()
      } else {
        val byId = bookDtoRepository.findAllByIds(ids, SearchContext(principal.user)).associateBy { it.id }
        ids.mapNotNull { byId[it] }
      }
    return WantToReadPageDto(
      items = items,
      total = wantToReadRepository.countByUserId(principal.user.id),
      page = safePage,
      size = safeSize,
    )
  }

  @GetMapping("book/{bookId}")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "Whether a book is in the current user want-to-read list")
  fun state(
    @AuthenticationPrincipal principal: LantaiPrincipal,
    @PathVariable bookId: String,
  ): WantToReadStateDto =
    WantToReadStateDto(
      wanted = wantToReadRepository.findByBookIdAndUserIdOrNull(bookId, principal.user.id) != null,
    )

  @PostMapping("book/{bookId}")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "Add a book to the current user want-to-read list")
  fun add(
    @AuthenticationPrincipal principal: LantaiPrincipal,
    @PathVariable bookId: String,
  ): WantToReadStateDto {
    requireBook(bookId)
    wantToReadRepository.save(WantToRead(bookId = bookId, userId = principal.user.id))
    return WantToReadStateDto(wanted = true)
  }

  @DeleteMapping("book/{bookId}")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "Remove a book from the current user want-to-read list")
  fun remove(
    @AuthenticationPrincipal principal: LantaiPrincipal,
    @PathVariable bookId: String,
  ): WantToReadStateDto {
    wantToReadRepository.delete(bookId, principal.user.id)
    return WantToReadStateDto(wanted = false)
  }

  @PostMapping("series/{seriesId}")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "Add every unread book of a series to the current user want-to-read list")
  fun addSeries(
    @AuthenticationPrincipal principal: LantaiPrincipal,
    @PathVariable seriesId: String,
  ): WantToReadSeriesResultDto {
    val bookIds = bookRepository.findAllBySeriesId(seriesId).filter { it.deletedDate == null }.map { it.id }
    if (bookIds.isEmpty()) return WantToReadSeriesResultDto(total = 0, added = 0, skipped = 0)

    val doneIds =
      readProgressRepository
        .findAllByBookIdsAndUserId(bookIds, principal.user.id)
        .filter { it.completed }
        .map { it.bookId }
        .toSet()
    val targets = bookIds.filter { it !in doneIds }
    val existing = wantToReadRepository.findBookIdsByUserIdAndBookIds(principal.user.id, targets).toSet()
    val toAdd = targets.filter { it !in existing }
    if (toAdd.isNotEmpty()) {
      wantToReadRepository.saveAll(toAdd.map { WantToRead(bookId = it, userId = principal.user.id) })
    }
    return WantToReadSeriesResultDto(
      total = bookIds.size,
      added = toAdd.size,
      skipped = bookIds.size - toAdd.size,
    )
  }

  @DeleteMapping("series/{seriesId}")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "Remove every book of a series from the current user want-to-read list")
  fun removeSeries(
    @AuthenticationPrincipal principal: LantaiPrincipal,
    @PathVariable seriesId: String,
  ): WantToReadSeriesResultDto {
    val bookIds = bookRepository.findAllBySeriesId(seriesId).map { it.id }
    if (bookIds.isEmpty()) return WantToReadSeriesResultDto(total = 0, added = 0, skipped = 0)
    val existing = wantToReadRepository.findBookIdsByUserIdAndBookIds(principal.user.id, bookIds)
    existing.forEach { wantToReadRepository.delete(it, principal.user.id) }
    return WantToReadSeriesResultDto(
      total = bookIds.size,
      added = 0,
      skipped = bookIds.size - existing.size,
    )
  }

  private fun requireBook(bookId: String) {
    bookRepository.findByIdOrNull(bookId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Book not found")
  }
}

data class WantToReadStateDto(
  val wanted: Boolean,
)

data class WantToReadSeriesResultDto(
  val total: Int,
  val added: Int,
  val skipped: Int,
)

data class WantToReadPageDto(
  val items: List<BookDto>,
  val total: Long,
  val page: Int,
  val size: Int,
)
