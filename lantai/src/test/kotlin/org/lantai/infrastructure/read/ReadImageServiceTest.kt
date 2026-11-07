package org.lantai.infrastructure.read

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.lantai.infrastructure.image.ImageAnalyzer
import org.lantai.infrastructure.mediacontainer.ContentDetector
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.imageio.ImageIO

class ReadImageServiceTest {
  private val analyzer = ImageAnalyzer()
  private val detector = mockk<ContentDetector> { every { detectMediaType(any<InputStream>()) } returns "image/png" }
  private val service = ReadImageService(analyzer, detector, ReadProperties())

  private fun transparentPng(
    width: Int,
    height: Int,
  ): ByteArray {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    ByteArrayOutputStream().use { out ->
      ImageIO.write(image, "png", out)
      return out.toByteArray()
    }
  }

  @Test
  fun `render downsizes to requested width`() {
    val rendered = service.render(transparentPng(1200, 1600), 300, "jpeg")
    assertThat(rendered.mediaType).isEqualTo("image/jpeg")
    val dimension = analyzer.getDimension(rendered.bytes.inputStream())
    assertThat(dimension).isNotNull
    assertThat(maxOf(dimension!!.width, dimension.height)).isLessThanOrEqualTo(320)
  }

  @Test
  fun `render flattens transparency onto white for jpeg`() {
    val rendered = service.render(transparentPng(400, 600), 200, "jpeg")
    val image = ImageIO.read(rendered.bytes.inputStream())
    val rgb = Color(image.getRGB(image.width / 2, image.height / 2)).let { Triple(it.red, it.green, it.blue) }
    assertThat(rgb).isEqualTo(Triple(255, 255, 255))
  }

  @Test
  fun `render keeps png output when requested`() {
    val rendered = service.render(transparentPng(800, 1200), 200, "png")
    assertThat(rendered.mediaType).isEqualTo("image/png")
    val dimension = analyzer.getDimension(rendered.bytes.inputStream())
    assertThat(maxOf(dimension!!.width, dimension.height)).isLessThanOrEqualTo(220)
  }

  @Test
  fun `render returns source when already smaller`() {
    val source = transparentPng(120, 160)
    val rendered = service.render(source, 300, "jpeg")
    assertThat(rendered.bytes).isEqualTo(source)
  }
}
