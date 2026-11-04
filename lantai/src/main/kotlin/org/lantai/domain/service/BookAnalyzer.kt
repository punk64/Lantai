package org.lantai.domain.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.lantai.domain.model.Book
import org.lantai.domain.model.BookPage
import org.lantai.domain.model.BookWithMedia
import org.lantai.domain.model.Dimension
import org.lantai.domain.model.Media
import org.lantai.domain.model.MediaExtensionEpub
import org.lantai.domain.model.MediaFile
import org.lantai.domain.model.MediaNotReadyException
import org.lantai.domain.model.MediaProfile
import org.lantai.domain.model.MediaType
import org.lantai.domain.model.MediaUnsupportedException
import org.lantai.domain.model.NoThumbnailFoundException
import org.lantai.domain.model.ThumbnailBook
import org.lantai.domain.model.TypedBytes
import org.lantai.domain.model.readablePath
import org.lantai.infrastructure.configuration.LantaiSettingsProvider
import org.lantai.infrastructure.hash.Hasher
import org.lantai.infrastructure.image.ImageAnalyzer
import org.lantai.infrastructure.image.ImageConverter
import org.lantai.infrastructure.image.ImageType
import org.lantai.infrastructure.mediacontainer.ContentDetector
import org.lantai.infrastructure.mediacontainer.divina.DivinaExtractor
import org.lantai.infrastructure.mediacontainer.epub.EpubExtractor
import org.lantai.infrastructure.mediacontainer.epub.epub
import org.lantai.infrastructure.mediacontainer.pdf.PdfExtractor
import org.lantai.infrastructure.mediacontainer.txt.TxtExtractor
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.extension

private val logger = KotlinLogging.logger {}
private val CALIBRE_FORMATS = setOf("mobi", "azw", "azw3", "prc", "fb2", "lit", "pdb")

@Service
class BookAnalyzer(
  private val contentDetector: ContentDetector,
  extractors: List<DivinaExtractor>,
  private val pdfExtractor: PdfExtractor,
  private val epubExtractor: EpubExtractor,
  private val txtExtractor: TxtExtractor,
  private val imageConverter: ImageConverter,
  private val imageAnalyzer: ImageAnalyzer,
  private val hasher: Hasher,
  @param:Value("#{@lantaiProperties.pageHashing}") private val pageHashing: Int,
  private val lantaiSettingsProvider: LantaiSettingsProvider,
  @Qualifier("thumbnailType")
  private val thumbnailType: ImageType,
  @Qualifier("pdfImageType")
  private val pdfImageType: ImageType,
) {
  val divinaExtractors =
    extractors
      .flatMap { e -> e.mediaTypes().map { it to e } }
      .toMap()

  fun analyze(
    book: Book,
    analyzeDimensions: Boolean,
  ): Media {
    logger.info { "Trying to analyze book: $book" }
    return try {
      val readable = book.copy(url = book.readablePath().toUri().toURL())
      var mediaType =
        if (readable.path.extension.lowercase() == "txt") {
          MediaType.TXT
        } else {
          contentDetector.detectMediaType(readable.path).let {
            logger.info { "Detected media type: $it" }
            MediaType.fromMediaType(it)
              ?: return Media(mediaType = it, status = Media.Status.UNSUPPORTED, comment = unsupportedComment(readable.path), bookId = book.id)
          }
        }

      if (readable.path.extension.lowercase() == "epub" && mediaType != MediaType.EPUB) {
        if (epubExtractor.isEpub(readable.path)) {
          mediaType = MediaType.EPUB
        } else {
          logger.warn { "Epub file is malformed, file is probably broken: ${readable.path}" }
          return Media(mediaType = mediaType.type, status = Media.Status.ERROR, comment = "ERR_1032", bookId = book.id)
        }
      }

      when (mediaType.profile) {
        MediaProfile.DIVINA -> analyzeDivina(readable, mediaType, analyzeDimensions)
        MediaProfile.PDF -> analyzePdf(readable, analyzeDimensions)
        MediaProfile.EPUB -> analyzeEpub(readable, analyzeDimensions)
        MediaProfile.TXT -> analyzeTxt(readable)
      }.copy(mediaType = mediaType.type)
    } catch (ade: AccessDeniedException) {
      logger.error(ade) { "Error while analyzing book: $book" }
      Media(status = Media.Status.ERROR, comment = "ERR_1000")
    } catch (ex: NoSuchFileException) {
      logger.error(ex) { "Error while analyzing book: $book" }
      Media(status = Media.Status.ERROR, comment = "ERR_1018")
    } catch (ex: Exception) {
      logger.error(ex) { "Error while analyzing book: $book" }
      Media(status = Media.Status.ERROR, comment = "ERR_1005")
    }.copy(bookId = book.id)
  }

  private fun analyzeDivina(
    book: Book,
    mediaType: MediaType,
    analyzeDimensions: Boolean,
  ): Media {
    val entries =
      try {
        divinaExtractors[mediaType.type]?.getEntries(book.path, analyzeDimensions)
          ?: return Media(status = Media.Status.UNSUPPORTED)
      } catch (ex: MediaUnsupportedException) {
        return Media(status = Media.Status.UNSUPPORTED, comment = ex.code)
      } catch (ex: Exception) {
        logger.error(ex) { "Error while analyzing book: $book" }
        return Media(status = Media.Status.ERROR, comment = "ERR_1008")
      }

    val (pages, others) =
      entries
        .partition { entry ->
          entry.mediaType?.let { contentDetector.isImage(it) } ?: false
        }.let { (images, others) ->
          Pair(
            images.map { BookPage(fileName = it.name, mediaType = it.mediaType!!, dimension = it.dimension, fileSize = it.fileSize) },
            others,
          )
        }

    val entriesErrorSummary =
      others
        .filter { it.mediaType.isNullOrBlank() }
        .map { it.name }
        .ifEmpty { null }
        ?.joinToString(prefix = "ERR_1007 [", postfix = "]") { it }

    if (pages.isEmpty()) {
      logger.warn { "Book $book does not contain any pages" }
      return Media(status = Media.Status.ERROR, comment = "ERR_1006")
    }
    logger.info { "Book has ${pages.size} pages" }

    val files = others.map { MediaFile(fileName = it.name, mediaType = it.mediaType, fileSize = it.fileSize) }

    return Media(status = Media.Status.READY, pages = pages, pageCount = pages.size, files = files, comment = entriesErrorSummary)
  }

  private fun analyzeEpub(
    book: Book,
    analyzeDimensions: Boolean,
  ): Media {
    book.path.epub { epub ->
      val (resources, missingResources) = epubExtractor.getResources(epub).partition { it.fileSize != null }
      val isKepub = epubExtractor.isKepub(epub, resources)

      val errors = mutableListOf<String>()

      val toc =
        try {
          epubExtractor.getToc(epub)
        } catch (e: Exception) {
          logger.error(e) { "Error while getting EPUB TOC" }
          errors.add("ERR_1035")
          emptyList()
        }

      val landmarks =
        try {
          epubExtractor.getLandmarks(epub)
        } catch (e: Exception) {
          logger.error(e) { "Error while getting EPUB Landmarks" }
          errors.add("ERR_1036")
          emptyList()
        }

      val pageList =
        try {
          epubExtractor.getPageList(epub)
        } catch (e: Exception) {
          logger.error(e) { "Error while getting EPUB page list" }
          errors.add("ERR_1037")
          emptyList()
        }

      val divinaPages =
        try {
          epubExtractor.getDivinaPages(epub, analyzeDimensions)
        } catch (e: Exception) {
          logger.error(e) { "Error while getting EPUB Divina pages" }
          errors.add("ERR_1038")
          emptyList()
        }

      val isFixedLayout = divinaPages.isNotEmpty() || epubExtractor.isFixedLayout(epub)

      val positions =
        try {
          epubExtractor.computePositions(epub, book, resources, isFixedLayout, isKepub, divinaPages)
        } catch (e: Exception) {
          logger.error(e) { "Error while getting EPUB positions" }
          errors.add("ERR_1039")
          emptyList()
        }

      val entriesErrorSummary =
        missingResources
          .map { it.fileName }
          .ifEmpty { null }
          ?.joinToString(prefix = "ERR_1033 [", postfix = "]") { it }

      val allErrors =
        (errors + entriesErrorSummary)
          .filterNotNull()
          .joinToString(" ")
          .ifBlank { null }

      return Media(
        status = Media.Status.READY,
        pages = divinaPages,
        files = resources,
        pageCount = if (divinaPages.isNotEmpty()) divinaPages.size else epubExtractor.computePageCount(epub),
        epubDivinaCompatible = divinaPages.isNotEmpty(),
        epubIsKepub = isKepub,
        extension =
          MediaExtensionEpub(
            toc = toc,
            landmarks = landmarks,
            pageList = pageList,
            isFixedLayout = isFixedLayout,
            positions = positions,
          ),
        comment = allErrors,
      )
    }
  }

  private fun analyzePdf(
    book: Book,
    analyzeDimensions: Boolean,
  ): Media {
    val pages = pdfExtractor.getPages(book.path, analyzeDimensions).map { BookPage(it.name, "", it.dimension) }
    return Media(status = Media.Status.READY, pages = pages)
  }

  private fun unsupportedComment(path: Path): String = if (path.extension.lowercase() in CALIBRE_FORMATS) "ERR_1042" else "ERR_1001"

  private fun analyzeTxt(book: Book): Media =
    try {
      val extension = txtExtractor.analyze(book.path)
      if (extension.chapters.isEmpty()) {
        Media(status = Media.Status.ERROR, comment = "ERR_1040")
      } else {
        Media(
          status = Media.Status.READY,
          pages =
            extension.chapters.map {
              BookPage(
                fileName = it.href,
                mediaType = MediaType.TXT.type,
                fileSize = it.byteEnd - it.byteStart,
              )
            },
          pageCount = extension.chapters.size,
          extension = extension,
        )
      }
    } catch (ex: Exception) {
      logger.error(ex) { "Error while analyzing txt book: $book" }
      Media(status = Media.Status.ERROR, comment = "ERR_1041")
    }

  @Throws(
    MediaNotReadyException::class,
    NoThumbnailFoundException::class,
  )
  fun generateThumbnail(book: BookWithMedia): ThumbnailBook {
    logger.info { "Generate thumbnail for book: $book" }

    if (book.media.status != Media.Status.READY) {
      logger.warn { "Book media is not ready, cannot generate thumbnail. Book: $book" }
      throw MediaNotReadyException()
    }

    val thumbnail =
      getPoster(book)?.let { cover ->
        imageConverter.resizeImageToByteArray(cover.bytes, thumbnailType, lantaiSettingsProvider.thumbnailSize.maxEdge)
      } ?: throw NoThumbnailFoundException()

    return ThumbnailBook(
      thumbnail = thumbnail,
      type = ThumbnailBook.Type.GENERATED,
      bookId = book.book.id,
      mediaType = thumbnailType.mediaType,
      dimension = imageAnalyzer.getDimension(thumbnail.inputStream()) ?: Dimension(0, 0),
      fileSize = thumbnail.size.toLong(),
    )
  }

  fun getPoster(book: BookWithMedia): TypedBytes? =
    when (book.media.profile) {
      MediaProfile.DIVINA -> divinaExtractors[book.media.mediaType]?.getPoster(book)
      MediaProfile.PDF -> pdfExtractor.getPageContentAsImage(book.book.path, 1)
      MediaProfile.EPUB -> epubExtractor.getCover(book.book.path) ?: if (book.media.epubDivinaCompatible) divinaExtractors[MediaType.ZIP.type]?.getPoster(book) else null
      MediaProfile.TXT -> null
      null -> null
    }

  private fun DivinaExtractor.getPoster(book: BookWithMedia): TypedBytes =
    this
      .getEntryStream(
        book.book.path,
        book.media.pages
          .first()
          .fileName,
      ).let {
        TypedBytes(
          it,
          book.media.pages
            .first()
            .mediaType,
        )
      }

  @Throws(
    MediaNotReadyException::class,
    IndexOutOfBoundsException::class,
  )
  fun getPageContent(
    book: BookWithMedia,
    number: Int,
  ): ByteArray {
    logger.debug { "Get page #$number for book: $book" }

    if (book.media.status != Media.Status.READY) {
      logger.warn { "Book media is not ready, cannot get pages" }
      throw MediaNotReadyException()
    }

    if (number > book.media.pageCount || number <= 0) {
      logger.error { "Page number #$number is out of bounds. Book has ${book.media.pageCount} pages" }
      throw IndexOutOfBoundsException("Page $number does not exist")
    }

    return when (book.media.profile) {
      MediaProfile.DIVINA -> divinaExtractors.getValue(book.media.mediaType!!).getEntryStream(book.book.readablePath(), book.media.pages[number - 1].fileName)
      MediaProfile.PDF -> pdfExtractor.getPageContentAsImage(book.book.readablePath(), number).bytes
      MediaProfile.EPUB ->
        if (book.media.epubDivinaCompatible)
          epubExtractor.getEntryStream(book.book.readablePath(), book.media.pages[number - 1].fileName)
        else
          throw MediaUnsupportedException("Epub profile does not support getting page content")

      MediaProfile.TXT -> throw MediaUnsupportedException("Txt profile does not support getting page content")

      null -> throw MediaNotReadyException()
    }
  }

  @Throws(
    MediaNotReadyException::class,
    IndexOutOfBoundsException::class,
  )
  fun getPageContentRaw(
    book: BookWithMedia,
    number: Int,
  ): TypedBytes {
    logger.debug { "Get raw page #$number for book: $book" }
    if (book.media.profile != MediaProfile.PDF) throw MediaUnsupportedException("Extractor does not support raw extraction of pages")

    if (book.media.status != Media.Status.READY) {
      logger.warn { "Book media is not ready, cannot get pages" }
      throw MediaNotReadyException()
    }

    if (number > book.media.pageCount || number <= 0) {
      logger.error { "Page number #$number is out of bounds. Book has ${book.media.pageCount} pages" }
      throw IndexOutOfBoundsException("Page $number does not exist")
    }

    return pdfExtractor.getPageContentAsPdf(book.book.readablePath(), number)
  }

  @Throws(
    MediaNotReadyException::class,
  )
  fun getFileContent(
    book: BookWithMedia,
    fileName: String,
  ): ByteArray {
    logger.debug { "Get file $fileName for book: $book" }

    if (book.media.status != Media.Status.READY) {
      logger.warn { "Book media is not ready, cannot get files" }
      throw MediaNotReadyException()
    }

    return when (book.media.profile) {
      MediaProfile.DIVINA -> divinaExtractors.getValue(book.media.mediaType!!).getEntryStream(book.book.readablePath(), fileName)
      MediaProfile.EPUB -> epubExtractor.getEntryStream(book.book.readablePath(), fileName)
      MediaProfile.TXT -> Files.readAllBytes(book.book.path)
      MediaProfile.PDF, null -> throw MediaUnsupportedException("Extractor does not support extraction of files")
    }
  }

  /**
   * Will hash the first and last pages of the given book.
   * The number of pages hashed from start/end is configurable.
   *
   * See [org.lantai.infrastructure.configuration.LantaiProperties.pageHashing]
   */
  fun hashPages(book: BookWithMedia): Media {
    val hashedPages =
      book.media.pages.mapIndexed { index, bookPage ->
        if (bookPage.fileHash.isBlank() && (index < pageHashing || index >= (book.media.pageCount - pageHashing))) {
          val content = getPageContent(book, index + 1)
          val hash = hashPage(bookPage, content)
          bookPage.copy(fileHash = hash)
        } else {
          bookPage
        }
      }

    return book.media.copy(pages = hashedPages)
  }

  /**
   * Hash a single page, using the file content for hashing.
   *
   * For JPEG, the image is read/written to remove the metadata.
   */
  fun hashPage(
    page: BookPage,
    content: ByteArray,
  ): String {
    val bytes =
      if (page.mediaType == ImageType.JPEG.mediaType) {
        // JPEG could contain different EXIF data, reading and writing back the image will get rid of it
        ByteArrayOutputStream().use { buffer ->
          ImageIO.write(ImageIO.read(content.inputStream()), ImageType.JPEG.imageIOFormat, buffer)
          buffer.toByteArray()
        }
      } else {
        content
      }

    return hasher.computeHash(bytes.inputStream())
  }

  fun getPdfPagesDynamic(media: Media): List<BookPage> {
    if (media.profile != MediaProfile.PDF) throw MediaUnsupportedException("Cannot get synthetic pages for non-PDF media")

    return media.pages.map { page ->
      page.copy(
        mediaType = pdfImageType.mediaType,
        dimension = page.dimension?.let { pdfExtractor.scaleDimension(it) },
      )
    }
  }
}
