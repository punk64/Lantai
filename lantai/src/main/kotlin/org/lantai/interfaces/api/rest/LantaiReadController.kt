package org.lantai.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.lantai.domain.model.Book
import org.lantai.domain.model.Media
import org.lantai.domain.model.SeriesMetadata
import org.lantai.domain.persistence.BookAnnotationRepository
import org.lantai.domain.persistence.BookMetadataRepository
import org.lantai.domain.persistence.BookRepository
import org.lantai.domain.persistence.MediaRepository
import org.lantai.domain.persistence.ReadProgressRepository
import org.lantai.domain.persistence.SeriesMetadataRepository
import org.lantai.infrastructure.read.ChapterBundleDto
import org.lantai.infrastructure.read.BookSearchResultDto
import org.lantai.infrastructure.read.BookSearchService
import org.lantai.infrastructure.read.ChapterContentService
import org.lantai.infrastructure.read.ChapterContext
import org.lantai.infrastructure.read.ChapterRefDto
import org.lantai.infrastructure.read.ImageRef
import org.lantai.infrastructure.read.ReadAnnotationDto
import org.lantai.infrastructure.read.ReadBookDto
import org.lantai.infrastructure.read.ReadBootstrapDto
import org.lantai.infrastructure.read.ReadChaptersDto
import org.lantai.infrastructure.read.ReadImageService
import org.lantai.infrastructure.read.ReadOutlinePageDto
import org.lantai.infrastructure.read.ReadOutlineVolumeDto
import org.lantai.infrastructure.read.ReadProgressDto
import org.lantai.infrastructure.read.ReadProperties
import org.lantai.infrastructure.security.LantaiPrincipal
import org.springframework.http.CacheControl
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.io.BufferedOutputStream
import java.nio.charset.StandardCharsets
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RestController
@RequestMapping("api/lantai/v1/read")
@Tag(name = "Lantai Read")
class LantaiReadController(
  private val bookRepository: BookRepository,
  private val mediaRepository: MediaRepository,
  private val bookMetadataRepository: BookMetadataRepository,
  private val seriesMetadataRepository: SeriesMetadataRepository,
  private val readProgressRepository: ReadProgressRepository,
  private val annotationRepository: BookAnnotationRepository,
  private val chapterContentService: ChapterContentService,
  private val bookSearchService: BookSearchService,
  private val readImageService: ReadImageService,
  private val readProperties: ReadProperties,
) {
  @GetMapping("{bookId}/bootstrap")
  @PreAuthorize("hasRole('PAGE_STREAMING')")
  @Operation(summary = "Read bootstrap payload")
  fun bootstrap(
    @AuthenticationPrincipal principal: LantaiPrincipal,
    @PathVariable bookId: String,
    @RequestParam(name = "chapter", required = false) chapter: Int?,
    @RequestParam(name = "window", required = false) window: Int?,
    @RequestParam(name = "images", required = false, defaultValue = "inline") images: String,
    @RequestParam(name = "w", required = false, defaultValue = "0") width: Int,
    @RequestParam(name = "fmt", required = false, defaultValue = "orig") format: String,
    request: HttpServletRequest,
  ): ReadBootstrapDto {
    val (book, media) = resolve(principal, bookId)
    val ctx = chapterContentService.context(media)
    val total = ctx.total
    if (total == 0) throw ResponseStatusException(HttpStatus.NOT_FOUND, "Book has no readable chapters")

    val index = ctx.indexByHref
    val progress = readProgressRepository.findByBookIdAndUserIdOrNull(book.id, principal.user.id)
    val progressChapter =
      progress?.locator?.href?.substringBefore('#')?.let { index[it] }
        ?: progress?.page?.takeIf { it in 1..total }
    val start = (chapter ?: progressChapter ?: 1).coerceIn(1, total)

    val span = (window ?: readProperties.defaultWindow).coerceIn(0, readProperties.maxWindow)
    val from = (start - span).coerceAtLeast(1)
    val to = (start + span).coerceAtMost(total)

    val bundle = build(book, media, ctx, start, images, width, format, request.contextPath)
    val annotations =
      annotationRepository.findAllByBookIdAndUserId(book.id, principal.user.id).map {
        ReadAnnotationDto(
          id = it.id,
          chapter = index[it.href.substringBefore('#')] ?: 0,
          href = it.href,
          color = it.color,
          marker = it.marker,
          note = it.note,
          textBefore = it.textBefore,
          textHighlight = it.textHighlight,
          textAfter = it.textAfter,
          progression = it.progression,
        )
      }

    return ReadBootstrapDto(
      book = readBookDto(book, media, total, request.contextPath),
      chapters = ctx.refs.subList(from - 1, to),
      from = from,
      to = to,
      total = total,
      progress =
        progress?.let {
          ReadProgressDto(
            chapter = progressChapter ?: start,
            href = it.locator?.href.orEmpty(),
            progression = (it.locator?.locations?.progression ?: 0f).toDouble(),
            totalProgression = it.locator?.locations?.totalProgression?.toDouble(),
            completed = it.completed,
          )
        },
      first = bundle,
      annotations = annotations,
    )
  }

  @GetMapping("{bookId}/chapters")
  @PreAuthorize("hasRole('PAGE_STREAMING')")
  @Operation(summary = "Read chapter window")
  fun chapters(
    @AuthenticationPrincipal principal: LantaiPrincipal,
    @PathVariable bookId: String,
    @RequestParam(name = "from", required = false, defaultValue = "1") from: Int,
    @RequestParam(name = "window", required = false) window: Int?,
    @RequestParam(name = "outline", required = false, defaultValue = "false") outline: Boolean,
  ): ReadChaptersDto {
    val (book, media) = resolve(principal, bookId)
    val ctx = chapterContentService.context(media)
    val total = ctx.total
    if (outline) {
      return ReadChaptersDto(
        chapters = emptyList(),
        from = 0,
        to = 0,
        total = total,
        outline = chapterContentService.outline(media, ctx),
      )
    }
    val span = (window ?: (readProperties.defaultWindow * 2 + 1)).coerceIn(1, readProperties.maxWindow)
    val start = from.coerceIn(1, maxOf(1, total))
    val end = (start + span - 1).coerceAtMost(total)
    return ReadChaptersDto(
      chapters = if (total == 0) emptyList() else ctx.refs.subList(start - 1, end),
      from = start,
      to = end,
      total = total,
      outline = emptyList(),
    )
  }

  @GetMapping("{bookId}/outline")
  @PreAuthorize("hasRole('PAGE_STREAMING')")
  @Operation(summary = "Read chapter outline page")
  fun outline(
    @AuthenticationPrincipal principal: LantaiPrincipal,
    @PathVariable bookId: String,
    @RequestParam(name = "from", required = false, defaultValue = "1") from: Int,
    @RequestParam(name = "window", required = false, defaultValue = "100") window: Int,
  ): ReadOutlinePageDto {
    val (book, media) = resolve(principal, bookId)
    val ctx = chapterContentService.context(media)
    val total = ctx.total
    val span = window.coerceIn(1, 500)
    val start = from.coerceIn(1, maxOf(1, total))
    val end = (start + span - 1).coerceAtMost(total)
    val placeholder = Regex("""^第 \d+ 节$""")
    val entries =
      if (total == 0) {
        emptyList()
      } else {
        ctx.refs.subList(start - 1, end).map { ref ->
          if (placeholder.matches(ref.title)) ref.copy(title = chapterContentService.pageHeading(book, media, ref.href) ?: ref.title) else ref
        }
      }
    val all = chapterContentService.outline(media, ctx)
    val tops = all.filter { it.depth == (all.minOfOrNull { entry -> entry.depth } ?: 0) }
    val holders = all.indices.filter { i -> i + 1 < all.size && all[i + 1].depth > all[i].depth }.map { all[it] }
    val outer = holders.filter { it.depth == (holders.minOfOrNull { entry -> entry.depth } ?: 0) }
    val picked =
      when {
        tops.size >= 2 && tops.size * 3 <= total -> tops
        outer.size >= 2 -> outer
        else -> emptyList()
      }
    val volumes = if (picked.size < 2) emptyList() else picked.map { ReadOutlineVolumeDto(it.n, it.title) }
    return ReadOutlinePageDto(entries, start, end, total, volumes)
  }

  @GetMapping("{bookId}/search")
  @PreAuthorize("hasRole('PAGE_STREAMING')")
  @Operation(summary = "Search inside a book")
  fun search(
    @AuthenticationPrincipal principal: LantaiPrincipal,
    @PathVariable bookId: String,
    @RequestParam(name = "q") query: String,
    @RequestParam(name = "limit", required = false, defaultValue = "30") limit: Int,
  ): BookSearchResultDto {
    val (book, media) = resolve(principal, bookId)
    return bookSearchService.search(book, media, query, limit)
  }

  @GetMapping("{bookId}/chapter/{number}")
  @PreAuthorize("hasRole('PAGE_STREAMING')")
  @Operation(summary = "Read chapter bundle")
  fun chapter(
    @AuthenticationPrincipal principal: LantaiPrincipal,
    @PathVariable bookId: String,
    @PathVariable number: Int,
    @RequestParam(name = "images", required = false, defaultValue = "inline") images: String,
    @RequestParam(name = "w", required = false, defaultValue = "0") width: Int,
    @RequestParam(name = "fmt", required = false, defaultValue = "orig") format: String,
    request: HttpServletRequest,
  ): ChapterBundleDto {
    val (book, media) = resolve(principal, bookId)
    return build(book, media, chapterContentService.context(media), number, images, width, format, request.contextPath)
  }

  @GetMapping("{bookId}/img/{ref}", produces = ["image/*"])
  @PreAuthorize("hasRole('PAGE_STREAMING')")
  @Operation(summary = "Read image resource")
  fun image(
    @AuthenticationPrincipal principal: LantaiPrincipal,
    @PathVariable bookId: String,
    @PathVariable ref: String,
    @RequestParam(name = "w", required = false, defaultValue = "0") width: Int,
    @RequestParam(name = "fmt", required = false, defaultValue = "orig") format: String,
  ): ResponseEntity<ByteArray> {
    val (book, media) = resolve(principal, bookId)
    val source =
      when (val target = readImageService.decodeRef(ref)) {
        is ImageRef.Entry -> chapterContentService.entryBytes(book, media, target.fileName)
        is ImageRef.Page -> chapterContentService.pageBytes(book, media, target.page)
        null -> throw ResponseStatusException(HttpStatus.NOT_FOUND)
      }
    val rendered = readImageService.render(source, width, format)
    return ResponseEntity
      .ok()
      .contentType(MediaType.parseMediaType(rendered.mediaType))
      .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePrivate().immutable())
      .body(rendered.bytes)
  }

  @GetMapping("{bookId}/thumb/{page}", produces = ["image/*"])
  @PreAuthorize("hasRole('PAGE_STREAMING')")
  @Operation(summary = "Read page thumbnail")
  fun thumb(
    @AuthenticationPrincipal principal: LantaiPrincipal,
    @PathVariable bookId: String,
    @PathVariable page: Int,
    @RequestParam(name = "w", required = false, defaultValue = "120") width: Int,
    @RequestParam(name = "fmt", required = false, defaultValue = "jpeg") format: String,
  ): ResponseEntity<ByteArray> {
    val (book, media) = resolve(principal, bookId)
    val source = chapterContentService.pageBytes(book, media, page)
    val rendered = readImageService.render(source, width, format)
    return ResponseEntity
      .ok()
      .contentType(MediaType.parseMediaType(rendered.mediaType))
      .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePrivate().immutable())
      .body(rendered.bytes)
  }

  @GetMapping("{bookId}/export", produces = ["application/zip"])
  @PreAuthorize("hasRole('PAGE_STREAMING')")
  @Operation(summary = "Export a page range as zip")
  fun export(
    @AuthenticationPrincipal principal: LantaiPrincipal,
    @PathVariable bookId: String,
    @RequestParam(name = "from", required = false, defaultValue = "1") from: Int,
    @RequestParam(name = "to") to: Int,
  ): ResponseEntity<StreamingResponseBody> {
    val (book, media) = resolve(principal, bookId)
    val total = media.pageCount
    if (total == 0) throw ResponseStatusException(HttpStatus.NOT_FOUND, "Book has no pages")
    val start = from.coerceIn(1, total)
    val end = to.coerceIn(start, total)
    if (end - start + 1 > readProperties.maxExportPages) {
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, "At most ${readProperties.maxExportPages} pages per export")
    }
    val name = "${book.name.substringBeforeLast('.')}-$start-$end.zip"
    val body =
      StreamingResponseBody { output ->
        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
          for (page in start..end) {
            val bytes = chapterContentService.pageBytes(book, media, page)
            zip.putNextEntry(ZipEntry("%04d.%s".format(page, imageExt(bytes))))
            zip.write(bytes)
            zip.closeEntry()
          }
        }
      }
    return ResponseEntity
      .ok()
      .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString())
      .contentType(MediaType.parseMediaType("application/zip"))
      .body(body)
  }

  private fun imageExt(bytes: ByteArray): String =
    when {
      bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "png"
      bytes.size >= 4 && bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() -> "gif"
      bytes.size >= 12 && bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() && bytes[10] == 0x42.toByte() -> "webp"
      bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
      else -> "img"
    }

  private fun build(
    book: Book,
    media: Media,
    ctx: ChapterContext,
    number: Int,
    images: String,
    width: Int,
    format: String,
    contextPath: String,
  ): ChapterBundleDto =
    try {
      chapterContentService.bundle(book, media, number, images, width, format, contextPath, ctx)
    } catch (_: IndexOutOfBoundsException) {
      throw ResponseStatusException(HttpStatus.NOT_FOUND, "Chapter $number does not exist")
    }

  private fun resolve(
    principal: LantaiPrincipal,
    bookId: String,
  ): Pair<Book, Media> {
    val book = bookRepository.findByIdOrNull(bookId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    if (!principal.user.canAccessLibrary(book.libraryId)) throw ResponseStatusException(HttpStatus.FORBIDDEN)
    val media = mediaRepository.findByIdOrNull(book.id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    return book to media
  }

  private fun readBookDto(
    book: Book,
    media: Media,
    total: Int,
    contextPath: String,
  ): ReadBookDto {
    val bookMetadata = bookMetadataRepository.findByIdOrNull(book.id)
    val seriesMetadata = seriesMetadataRepository.findByIdOrNull(book.seriesId)
    return ReadBookDto(
      id = book.id,
      title = bookMetadata?.title?.takeIf { it.isNotBlank() } ?: book.name,
      seriesTitle = seriesMetadata?.title.orEmpty(),
      number = bookMetadata?.number?.takeIf { it.isNotBlank() } ?: formatNumber(bookMetadata?.numberSort),
      profile = media.profile?.name.orEmpty(),
      chapterCount = total,
      readingDirection = directionOf(seriesMetadata),
      coverUrl = "$contextPath/api/v1/books/${book.id}/thumbnail",
      modified = media.lastModifiedDate.toEpochSecond(ZoneOffset.UTC),
    )
  }

  private fun directionOf(seriesMetadata: SeriesMetadata?): String =
    when (seriesMetadata?.readingDirection) {
      SeriesMetadata.ReadingDirection.LEFT_TO_RIGHT -> "LTR"
      SeriesMetadata.ReadingDirection.RIGHT_TO_LEFT -> "RTL"
      SeriesMetadata.ReadingDirection.VERTICAL -> "TTB"
      SeriesMetadata.ReadingDirection.WEBTOON -> "WEBTOON"
      null -> "LTR"
    }

  private fun formatNumber(numberSort: Float?): String {
    if (numberSort == null) return ""
    return if (numberSort % 1f == 0f) numberSort.toInt().toString() else String.format("%.1f", numberSort)
  }
}
