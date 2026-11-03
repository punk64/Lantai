package org.lantai.interfaces.api.rest

import com.jakewharton.byteunits.BinaryByteUnit
import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.lantai.domain.model.CodedException
import org.lantai.domain.model.MediaNotReadyException
import org.lantai.domain.model.MediaProfile
import org.lantai.domain.model.TransientBook
import org.lantai.domain.persistence.TransientBookRepository
import org.lantai.domain.service.BookAnalyzer
import org.lantai.domain.service.TransientBookLifecycle
import org.lantai.infrastructure.openapi.OpenApiConfiguration
import org.lantai.infrastructure.web.getMediaTypeOrDefault
import org.lantai.infrastructure.web.toFilePath
import org.lantai.interfaces.api.getBookLastModified
import org.lantai.interfaces.api.rest.dto.PageDto
import org.lantai.interfaces.api.setNotModified
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.ServletWebRequest
import org.springframework.web.server.ResponseStatusException
import java.nio.file.NoSuchFileException
import java.time.LocalDateTime

private val logger = KotlinLogging.logger {}

@RestController
@RequestMapping("api/v1/transient-books", produces = [MediaType.APPLICATION_JSON_VALUE])
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = OpenApiConfiguration.TagNames.BOOK_IMPORT)
class TransientBooksController(
  private val transientBookLifecycle: TransientBookLifecycle,
  private val transientBookRepository: TransientBookRepository,
  private val bookAnalyzer: BookAnalyzer,
) {
  @PostMapping
  @Operation(summary = "Scan folder for transient books", description = "Scan provided folder for transient books.")
  fun scanTransientBooks(
    @RequestBody request: ScanRequestDto,
  ): List<TransientBookDto> =
    try {
      transientBookLifecycle
        .scanAndPersist(request.path)
        .sortedBy { it.book.path }
        .map { it.toDto() }
    } catch (e: CodedException) {
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.code)
    }

  @PostMapping("{id}/analyze")
  @Operation(summary = "Analyze transient book")
  fun analyzeTransientBook(
    @PathVariable id: String,
  ): TransientBookDto =
    transientBookRepository.findByIdOrNull(id)?.let {
      transientBookLifecycle.analyzeAndPersist(it).toDto()
    } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

  @GetMapping(
    value = ["{id}/pages/{pageNumber}"],
    produces = [MediaType.ALL_VALUE],
  )
  @Operation(summary = "Get transient book page")
  fun getPageByTransientBookId(
    @PathVariable id: String,
    @PathVariable pageNumber: Int,
    request: ServletWebRequest,
  ): ResponseEntity<ByteArray> =
    transientBookRepository.findByIdOrNull(id)?.let {
      if (request.checkNotModified(getBookLastModified(it.media))) {
        return@let ResponseEntity
          .status(HttpStatus.NOT_MODIFIED)
          .setNotModified(it.media)
          .body(ByteArray(0))
      }

      try {
        val pageContent = transientBookLifecycle.getBookPage(it, pageNumber)

        ResponseEntity
          .ok()
          .contentType(getMediaTypeOrDefault(pageContent.mediaType))
          .setNotModified(it.media)
          .body(pageContent.bytes)
      } catch (ex: IndexOutOfBoundsException) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Page number does not exist")
      } catch (ex: MediaNotReadyException) {
        throw ResponseStatusException(HttpStatus.NOT_FOUND, "Book analysis failed")
      } catch (ex: NoSuchFileException) {
        logger.warn(ex) { "File not found}" }
        throw ResponseStatusException(HttpStatus.NOT_FOUND, "File not found, it may have moved")
      }
    } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

  private fun TransientBook.toDto(): TransientBookDto {
    val pages = if (media.profile == MediaProfile.PDF) bookAnalyzer.getPdfPagesDynamic(media) else media.pages
    return TransientBookDto(
      id = book.id,
      name = book.name,
      url = book.url.toFilePath(),
      fileLastModified = book.fileLastModified,
      sizeBytes = book.fileSize,
      status = media.status.toString(),
      mediaType = media.mediaType ?: "",
      pages =
        pages.mapIndexed { index, bookPage ->
          PageDto(
            number = index + 1,
            fileName = bookPage.fileName,
            mediaType = bookPage.mediaType,
            width = bookPage.dimension?.width,
            height = bookPage.dimension?.height,
            sizeBytes = bookPage.fileSize,
          )
        },
      files = media.files.map { it.fileName },
      comment = media.comment ?: "",
      number = metadata.number,
      seriesId = metadata.seriesId,
    )
  }
}

data class ScanRequestDto(
  val path: String,
)

data class TransientBookDto(
  val id: String,
  val name: String,
  val url: String,
  val fileLastModified: LocalDateTime,
  val sizeBytes: Long,
  val size: String = BinaryByteUnit.format(sizeBytes),
  val status: String,
  val mediaType: String,
  val pages: List<PageDto>,
  val files: List<String>,
  val comment: String,
  val number: Float?,
  val seriesId: String?,
)
