package org.lantai.infrastructure.online

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class ExtensionIndexParserTest {
  @Test
  fun `parses gzipped store index`() {
    val entries = ExtensionIndexParser.parse(gzip(buildIndex()))

    assertThat(entries).hasSize(2)
    val first = entries.first()
    assertThat(first.name).isEqualTo("Kuaikanmanhua")
    assertThat(first.pkgName).isEqualTo("eu.kanade.tachiyomi.extension.zh.kuaikanmanhua")
    assertThat(first.versionName).isEqualTo("1.6.0")
    assertThat(first.versionCode).isEqualTo(106000)
    assertThat(first.lang).isEqualTo("zh")
    assertThat(first.jarUrl).isEqualTo("https://example.com/kuaikan-v1.6.0.jar")
    assertThat(first.apkUrl).isEqualTo("https://example.com/kuaikan-v1.6.0.apk")
    assertThat(first.iconUrl).isEqualTo("https://example.com/icon.png")
  }

  @Test
  fun `parses plain payload`() {
    val entries = ExtensionIndexParser.parse(buildIndex())
    assertThat(entries).hasSize(2)
    assertThat(entries[1].pkgName).isEqualTo("eu.kanade.tachiyomi.extension.zh.tencentcomics")
  }

  @Test
  fun `skips entries without package name`() {
    val broken = field(101, field(1, field(1, text("Nameless"))))
    assertThat(ExtensionIndexParser.parse(broken)).isEmpty()
  }

  private fun buildIndex(): ByteArray {
    val kuaikan =
      field(1, text("Kuaikanmanhua")) +
        field(2, text("eu.kanade.tachiyomi.extension.zh.kuaikanmanhua")) +
        field(
          3,
          field(1, text("https://example.com/kuaikan-v1.6.0.apk")) +
            field(2, text("https://example.com/icon.png")) +
            field(501, text("https://example.com/kuaikan-v1.6.0.jar")),
        ) +
        field(4, text("1.6")) +
        varintField(5, 106000) +
        field(6, text("1.6.0")) +
        varintField(7, 1) +
        field(8, field(1, text("1")) + field(2, text("Kuaikan")) + field(3, text("zh")))

    val tencent =
      field(1, text("Tencent Comics")) +
        field(2, text("eu.kanade.tachiyomi.extension.zh.tencentcomics")) +
        field(3, field(1, text("https://example.com/tencent-v1.6.0.jar"))) +
        field(6, text("1.6.0")) +
        varintField(5, 106000) +
        field(8, field(3, text("zh-Hans")))

    return field(1, text("Keiyoushi")) +
      field(2, text("KEI")) +
      field(101, field(1, kuaikan) + field(1, tencent))
  }

  private fun gzip(bytes: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    GZIPOutputStream(out).use { it.write(bytes) }
    return out.toByteArray()
  }

  private fun text(value: String): ByteArray = value.toByteArray(Charsets.UTF_8)

  private fun field(
    number: Int,
    bytes: ByteArray,
  ): ByteArray = varint((number shl 3) or 2) + varint(bytes.size) + bytes

  private fun varintField(
    number: Int,
    value: Int,
  ): ByteArray = varint(number shl 3) + varint(value)

  private fun varint(value: Int): ByteArray {
    var current = value
    val out = ByteArrayOutputStream()
    while (true) {
      val chunk = current and 0x7F
      current = current ushr 7
      if (current == 0) {
        out.write(chunk)
        break
      }
      out.write(chunk or 0x80)
    }
    return out.toByteArray()
  }
}
