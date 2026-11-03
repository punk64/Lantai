package org.lantai.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.lantai.domain.persistence.BookRepository
import org.lantai.domain.service.BookLifecycle
import org.lantai.infrastructure.app.AppCapabilitiesDto
import org.lantai.infrastructure.app.AppHomeDto
import org.lantai.infrastructure.app.AppHomeService
import org.lantai.infrastructure.app.AppProperties
import org.lantai.infrastructure.online.OnlineReadService
import org.lantai.infrastructure.read.ReadImageService
import org.lantai.infrastructure.security.LantaiPrincipal
import org.lantai.infrastructure.web.setCachePrivate
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.concurrent.TimeUnit

@RestController
@RequestMapping("api/lantai/v1/app")
@Tag(name = "Lantai App")
class LantaiAppController(
  private val appHomeService: AppHomeService,
  private val appProperties: AppProperties,
  private val bookRepository: BookRepository,
  private val bookLifecycle: BookLifecycle,
  private val onlineReadService: OnlineReadService,
  private val readImageService: ReadImageService,
) {
  @GetMapping("home")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "Aggregated home payload for clients")
  fun home(
    @AuthenticationPrincipal principal: LantaiPrincipal,
    @RequestParam(name = "limit", required = false) limit: Int?,
    @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) ifNoneMatch: String?,
    request: HttpServletRequest,
  ): ResponseEntity<AppHomeDto> {
    val result =
      appHomeService.home(
        principal.user,
        limit ?: appProperties.homeLimit,
        request.contextPath,
      )
    if (matchIfNoneMatch(ifNoneMatch, result.etag)) {
      return ResponseEntity
        .status(HttpStatus.NOT_MODIFIED)
        .eTag(result.etag)
        .setCachePrivate()
        .build()
    }
    return ResponseEntity.ok().eTag(result.etag).setCachePrivate().body(result.body)
  }

  @GetMapping("cover/{bookId}", produces = ["image/*"])
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "Book cover resized on demand")
  fun cover(
    @AuthenticationPrincipal principal: LantaiPrincipal,
    @PathVariable bookId: String,
    @RequestParam(name = "w", required = false, defaultValue = "240") width: Int,
    @RequestParam(name = "fmt", required = false, defaultValue = "jpeg") format: String,
  ): ResponseEntity<ByteArray> {
    val book = bookRepository.findByIdOrNull(bookId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    if (!principal.user.canAccessLibrary(book.libraryId)) throw ResponseStatusException(HttpStatus.FORBIDDEN)
    onlineReadService.ensureBookThumbnail(bookId)
    val source = bookLifecycle.getThumbnailBytes(bookId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    val rendered = readImageService.render(source.bytes, width.coerceIn(0, MAX_COVER_WIDTH), format)
    return ResponseEntity
      .ok()
      .contentType(MediaType.parseMediaType(rendered.mediaType))
      .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePrivate().immutable())
      .body(rendered.bytes)
  }

  @GetMapping("capabilities")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "Client capability negotiation")
  fun capabilities(): AppCapabilitiesDto = AppCapabilitiesDto(pagination = appHomeService.limits())

  private fun matchIfNoneMatch(
    header: String?,
    etag: String,
  ): Boolean {
    if (header.isNullOrBlank()) return false
    if (header.trim() == "*") return true
    return header.split(',').any { it.trim().removePrefix("W/").trim('"') == etag }
  }

  companion object {
    private const val MAX_COVER_WIDTH = 1600
  }
}
