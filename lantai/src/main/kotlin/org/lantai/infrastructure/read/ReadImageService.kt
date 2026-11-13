package org.lantai.infrastructure.read

import net.coobird.thumbnailator.Thumbnails
import org.lantai.infrastructure.image.ImageAnalyzer
import org.lantai.infrastructure.image.ImageType
import org.lantai.infrastructure.mediacontainer.ContentDetector
import org.springframework.stereotype.Service
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.max

data class RenderedImage(
  val bytes: ByteArray,
  val mediaType: String,
)

sealed interface ImageRef {
  data class Entry(
    val fileName: String,
  ) : ImageRef

  data class Page(
    val page: Int,
  ) : ImageRef
}

@Service
class ReadImageService(
  private val imageAnalyzer: ImageAnalyzer,
  private val contentDetector: ContentDetector,
  private val readProperties: ReadProperties,
) {
  companion object {
    private const val ENTRY_PREFIX = "e:"
    private const val PAGE_PREFIX = "p:"
  }

  fun refOfEntry(fileName: String): String = encode(ENTRY_PREFIX + fileName)

  fun refOfPage(page: Int): String = encode(PAGE_PREFIX + page)

  fun decodeRef(ref: String): ImageRef? {
    val raw =
      try {
        String(Base64.getUrlDecoder().decode(ref), Charsets.UTF_8)
      } catch (_: IllegalArgumentException) {
        return null
      }
    return when {
      raw.startsWith(ENTRY_PREFIX) -> raw.removePrefix(ENTRY_PREFIX).takeIf { it.isNotBlank() }?.let { ImageRef.Entry(it) }
      raw.startsWith(PAGE_PREFIX) -> raw.removePrefix(PAGE_PREFIX).toIntOrNull()?.takeIf { it > 0 }?.let { ImageRef.Page(it) }
      else -> null
    }
  }

  fun dataUri(bytes: ByteArray, mediaType: String): String = "data:$mediaType;base64," + Base64.getEncoder().encodeToString(bytes)

  fun render(
    bytes: ByteArray,
    width: Int,
    format: String,
  ): RenderedImage {
    val sourceType = contentDetector.detectMediaType(bytes.inputStream())
    if (width <= 0) return RenderedImage(bytes, sourceType)

    val longest =
      runCatching { imageAnalyzer.getDimension(bytes.inputStream()) }.getOrNull()?.let { max(it.width, it.height) }
        ?: return RenderedImage(bytes, sourceType)
    if (longest <= width) return RenderedImage(bytes, sourceType)

    val target = pickType(sourceType, format)
    val output = encode(bytes, width, target) ?: return RenderedImage(bytes, sourceType)

    return RenderedImage(output, target.mediaType)
  }

  private fun encode(
    bytes: ByteArray,
    width: Int,
    target: ImageType,
  ): ByteArray? =
    runCatching {
      val sampled = decodeSampled(bytes, width)
      val builder =
        when {
          sampled == null -> Thumbnails.of(bytes.inputStream())
          target == ImageType.JPEG -> Thumbnails.of(flattenOnWhite(sampled))
          else -> Thumbnails.of(sampled)
        }
      ByteArrayOutputStream().use { buffer ->
        builder
          .size(width, width)
          .imageType(if (target == ImageType.PNG) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
          .outputFormat(target.imageIOFormat)
          .outputQuality(if (target == ImageType.JPEG) readProperties.jpegQuality else 1.0)
          .toOutputStream(buffer)
        buffer.toByteArray()
      }
    }.getOrNull()

  private fun decodeSampled(
    bytes: ByteArray,
    width: Int,
  ): BufferedImage? =
    runCatching {
      ImageIO.createImageInputStream(ByteArrayInputStream(bytes))?.use { input ->
        val readers = ImageIO.getImageReaders(input)
        if (!readers.hasNext()) {
          null
        } else {
          val reader = readers.next()
          try {
            reader.input = input
            val longest = max(reader.getWidth(0), reader.getHeight(0))
            val param = reader.defaultReadParam
            val step = max(1, longest / (width * 2))
            if (step > 1) param.setSourceSubsampling(step, step, 0, 0)
            reader.read(0, param)
          } finally {
            reader.dispose()
          }
        }
      }
    }.getOrNull()

  private fun flattenOnWhite(source: BufferedImage): BufferedImage {
    if (!source.colorModel.hasAlpha()) return source
    val flat = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB)
    flat.createGraphics().apply {
      color = Color.WHITE
      fillRect(0, 0, flat.width, flat.height)
      drawImage(source, 0, 0, null)
      dispose()
    }
    return flat
  }

  private fun encode(raw: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(Charsets.UTF_8))

  private fun pickType(
    sourceType: String,
    format: String,
  ): ImageType =
    when {
      format.equals("png", true) -> ImageType.PNG
      format.equals("jpeg", true) || format.equals("jpg", true) -> ImageType.JPEG
      sourceType == "image/png" || sourceType == "image/gif" || sourceType == "image/webp" -> ImageType.PNG
      else -> ImageType.JPEG
    }
}
