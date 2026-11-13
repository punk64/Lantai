package org.lantai.infrastructure.mediacontainer.divina

import org.apache.tika.config.TikaConfig
import org.assertj.core.api.Assertions.assertThat
import org.lantai.domain.model.Dimension
import org.lantai.infrastructure.image.ImageAnalyzer
import org.lantai.infrastructure.mediacontainer.ContentDetector
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource

class ZipExtractorTest {
  private val contentDetector = ContentDetector(TikaConfig())
  private val imageAnalyzer = ImageAnalyzer()
  private val zipExtractor = ZipExtractor(contentDetector, imageAnalyzer)

  @Test
  fun `given zip file when parsing for entries then returns all images`() {
    val fileResource = ClassPathResource("archives/zip.zip")

    val entries = zipExtractor.getEntries(fileResource.file.toPath(), true)

    assertThat(entries).hasSize(1)
    with(entries.first()) {
      assertThat(name).isEqualTo("lantai.png")
      assertThat(mediaType).isEqualTo("image/png")
      assertThat(dimension).isEqualTo(Dimension(48, 48))
      assertThat(fileSize).isEqualTo(3108)
    }
  }

  @Test
  fun `given zip file when parsing for entries without analyzing dimensions then returns all images without dimensions`() {
    val fileResource = ClassPathResource("archives/zip.zip")

    val entries = zipExtractor.getEntries(fileResource.file.toPath(), false)

    assertThat(entries).hasSize(1)
    with(entries.first()) {
      assertThat(name).isEqualTo("lantai.png")
      assertThat(mediaType).isEqualTo("image/png")
      assertThat(dimension).isNull()
      assertThat(fileSize).isEqualTo(3108)
    }
  }
}
