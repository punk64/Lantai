package org.lantai.infrastructure.metadata.barcode

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.commons.validator.routines.ISBNValidator
import org.lantai.domain.model.BookMetadataPatch
import org.lantai.domain.model.BookMetadataPatchCapability
import org.lantai.domain.model.BookWithMedia
import org.lantai.domain.model.Library
import org.lantai.domain.model.MediaProfile
import org.lantai.domain.model.MetadataPatchTarget
import org.lantai.domain.service.BookAnalyzer
import org.lantai.infrastructure.metadata.BookMetadataProvider
import org.springframework.stereotype.Service
import java.util.EnumSet
import javax.imageio.ImageIO

private val logger = KotlinLogging.logger {}

private const val PAGES_LAST = 3
private const val PAGES_FIRST = 3

@Service
class IsbnBarcodeProvider(
  private val bookAnalyzer: BookAnalyzer,
  private val validator: ISBNValidator,
) : BookMetadataProvider {
  private val hints =
    mapOf(
      DecodeHintType.POSSIBLE_FORMATS to EnumSet.of(BarcodeFormat.EAN_13),
      DecodeHintType.TRY_HARDER to true,
    )

  override val capabilities = setOf(BookMetadataPatchCapability.ISBN)

  override fun getBookMetadataFromBook(book: BookWithMedia): BookMetadataPatch? {
    if (book.media.profile == MediaProfile.EPUB) return null

    val pagesToTry =
      (1..book.media.pageCount).toList().let {
        (it.takeLast(PAGES_LAST).reversed() + it.take(PAGES_FIRST)).distinct()
      }

    for (p in pagesToTry) {
      try {
        val imageBytes = bookAnalyzer.getPageContent(book, p)
        ImageIO.read(imageBytes.inputStream())?.let { image ->
          val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
          val source = RGBLuminanceSource(image.width, image.height, pixels)
          val bitmap = BinaryBitmap(HybridBinarizer(source))

          val result =
            try {
              MultiFormatReader().decode(bitmap, hints)
            } catch (e: Exception) {
              null
            }

          if (result == null || result.text == null) {
            logger.debug { "Book page $p does not contain a barcode: $book" }
          } else {
            if (validator.isValid(result.text)) {
              logger.debug { "Book page $p contains barcode which is valid ISBN: '${result.text}'. $book" }
              return BookMetadataPatch(isbn = validator.validate(result.text))
            } else {
              logger.debug { "Book page $p contains barcode which is invalid ISBN: '${result.text}'. $book" }
            }
          }
        }
      } catch (e: Exception) {
        logger.error(e) { "Error while processing page" }
      }
    }

    return null
  }

  override fun shouldLibraryHandlePatch(
    library: Library,
    target: MetadataPatchTarget,
  ): Boolean =
    when (target) {
      MetadataPatchTarget.BOOK -> library.importBarcodeIsbn
      else -> false
    }
}
