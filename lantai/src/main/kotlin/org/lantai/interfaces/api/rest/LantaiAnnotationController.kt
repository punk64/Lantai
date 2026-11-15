package org.lantai.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.lantai.domain.model.BookAnnotation
import org.lantai.domain.persistence.BookAnnotationRepository
import org.lantai.infrastructure.security.LantaiPrincipal
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime

@RestController
@RequestMapping("api/lantai/v1/annotations", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Lantai Annotations")
class LantaiAnnotationController(
  private val repository: BookAnnotationRepository,
) {
  @GetMapping
  @Operation(summary = "List my annotations of a book")
  fun list(
    @AuthenticationPrincipal principal: LantaiPrincipal,
    @RequestParam bookId: String,
  ): List<BookAnnotationDto> = repository.findAllByBookIdAndUserId(bookId, principal.user.id).map { it.toDto() }

  @PostMapping
  @Operation(summary = "Create an annotation with a locator and a text fallback")
  fun create(
    @AuthenticationPrincipal principal: LantaiPrincipal,
    @RequestBody body: BookAnnotationCreationDto,
  ): BookAnnotationDto {
    if (body.href.isBlank()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "href is required")
    val marker = parseMarker(body.marker)
    val annotation =
      BookAnnotation(
        bookId = body.bookId,
        userId = principal.user.id,
        href = body.href,
        fragment = body.fragment.orEmpty(),
        progression = body.progression,
        textBefore = body.textBefore.orEmpty(),
        textHighlight = body.textHighlight.orEmpty(),
        textAfter = body.textAfter.orEmpty(),
        color = body.color?.takeIf { it.isNotBlank() } ?: DEFAULT_COLOR,
        marker = marker,
        note = body.note.orEmpty(),
        selection = body.selection,
      )
    repository.insert(annotation)
    return annotation.toDto()
  }

  @PatchMapping("{id}")
  @Operation(summary = "Update note, color or marker of my annotation")
  fun update(
    @AuthenticationPrincipal principal: LantaiPrincipal,
    @PathVariable id: String,
    @RequestBody body: BookAnnotationUpdateDto,
  ): BookAnnotationDto {
    val annotation = ownedOrNull(id, principal.user.id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Annotation $id does not exist")
    val updated =
      annotation.copy(
        note = body.note ?: annotation.note,
        color = body.color ?: annotation.color,
        marker = body.marker?.let { parseMarker(it) } ?: annotation.marker,
        progression = body.progression ?: annotation.progression,
        lastModifiedDate = LocalDateTime.now(),
      )
    repository.update(updated)
    return updated.toDto()
  }

  @DeleteMapping("{id}")
  @Operation(summary = "Delete my annotation")
  fun delete(
    @AuthenticationPrincipal principal: LantaiPrincipal,
    @PathVariable id: String,
  ) {
    ownedOrNull(id, principal.user.id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Annotation $id does not exist")
    repository.delete(id)
  }

  private fun ownedOrNull(
    id: String,
    userId: String,
  ): BookAnnotation? = repository.findByIdOrNull(id)?.takeIf { it.userId == userId }

  private fun parseMarker(raw: String?): String {
    val normalized = raw?.trim()?.uppercase()?.takeIf { it.isNotBlank() } ?: BookAnnotation.Marker.HIGHLIGHT.name
    if (normalized !in BookAnnotation.Marker.entries.map { it.name }) {
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown marker: $raw")
    }
    return normalized
  }

  private fun BookAnnotation.toDto() =
    BookAnnotationDto(
      id = id,
      bookId = bookId,
      href = href,
      fragment = fragment,
      progression = progression,
      textBefore = textBefore,
      textHighlight = textHighlight,
      textAfter = textAfter,
      color = color,
      marker = marker,
      note = note,
      selection = selection,
      createdDate = createdDate.toString(),
      lastModifiedDate = lastModifiedDate.toString(),
    )

  private companion object {
    const val DEFAULT_COLOR = "#FFEB3B"
  }
}

data class BookAnnotationCreationDto(
  val bookId: String,
  val href: String,
  val fragment: String? = null,
  val progression: Double? = null,
  val textBefore: String? = null,
  val textHighlight: String? = null,
  val textAfter: String? = null,
  val color: String? = null,
  val marker: String? = null,
  val note: String? = null,
  val selection: String? = null,
)

data class BookAnnotationUpdateDto(
  val note: String? = null,
  val color: String? = null,
  val marker: String? = null,
  val progression: Double? = null,
)

data class BookAnnotationDto(
  val id: String,
  val bookId: String,
  val href: String,
  val fragment: String,
  val progression: Double?,
  val textBefore: String,
  val textHighlight: String,
  val textAfter: String,
  val color: String,
  val marker: String,
  val note: String,
  val selection: String?,
  val createdDate: String,
  val lastModifiedDate: String,
)
