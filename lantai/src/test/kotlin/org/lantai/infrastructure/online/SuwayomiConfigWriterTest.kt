package org.lantai.infrastructure.online

import org.assertj.core.api.Assertions.assertThat
import org.lantai.infrastructure.configuration.LantaiProperties
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

class SuwayomiConfigWriterTest {
  @TempDir
  lateinit var tempDir: Path

  @Test
  fun `generates config with managed credentials`() {
    val writer = newWriter(tempDir.resolve("suwayomi").toString())
    val credentials = writer.ensureConfig()

    assertThat(credentials.username).isEqualTo("lantai")
    assertThat(credentials.password).hasSize(32)
    assertThat(credentials.port).isEqualTo(25610)

    val content = writer.serverConf().readText()
    assertThat(content).contains("server.ip = \"127.0.0.1\"")
    assertThat(content).contains("server.port = 25610")
    assertThat(content).contains("server.authMode = BASIC_AUTH")
    assertThat(content).contains("server.authUsername = \"lantai\"")
    assertThat(content).contains("server.authPassword = \"${credentials.password}\"")
    assertThat(content).contains("server.downloadAsCbz = true")
    assertThat(content).contains("server.kcefEnabled = false")
    assertThat(content).contains("server.initialOpenInBrowserEnabled = false")
    assertThat(content).contains("server.webUIChannel = BUNDLED")
    assertThat(content).contains("server.extensionStores = [\"https://github.com/keiyoushi/extensions/raw/repo/index.pb\"]")
    assertThat(content).contains("server.downloadsPath = \"${tempDir.resolve("suwayomi").resolve("data").resolve("downloads").toString().replace("\\", "/")}\"")
  }

  @Test
  fun `reuses existing password on repeated calls`() {
    val writer = newWriter(tempDir.resolve("suwayomi").toString())
    val first = writer.ensureConfig()
    val second = writer.ensureConfig()

    assertThat(second.password).isEqualTo(first.password)
    assertThat(second.username).isEqualTo(first.username)
  }

  @Test
  fun `rotatePassword changes the password`() {
    val writer = newWriter(tempDir.resolve("suwayomi").toString())
    val first = writer.ensureConfig()
    val rotated = writer.rotatePassword()

    assertThat(rotated.password).isNotEqualTo(first.password)
    assertThat(writer.serverConf().readText()).contains("server.authPassword = \"${rotated.password}\"")
  }

  @Test
  fun `preserves inline comments when updating existing keys`() {
    val home = tempDir.resolve("suwayomi")
    val writer = newWriter(home.toString())
    Files.createDirectories(home.resolve("data"))
    Files.writeString(
      writer.serverConf(),
      """
      server.port = 4567 # default: 4567 ; range: [1, 65535]
      server.authMode = NONE # default: NONE
      """.trimIndent() + "\n",
    )

    val credentials = writer.ensureConfig()

    val content = writer.serverConf().readText()
    assertThat(credentials.port).isEqualTo(25610)
    assertThat(content).contains("server.port = 25610 # default: 4567 ; range: [1, 65535]")
    assertThat(content).contains("server.authMode = BASIC_AUTH # default: NONE")
  }

  @Test
  fun `derives home from downloads directory`() {
    val properties =
      OnlineProperties().apply {
        suwayomiDownloadsDir = tempDir.resolve("suwayomi").resolve("data").resolve("downloads").toString()
      }
    val writer = SuwayomiConfigWriter(properties, LantaiProperties())

    assertThat(writer.home()).isEqualTo(tempDir.resolve("suwayomi").toAbsolutePath().normalize())
    assertThat(writer.effectiveDownloadsDir()).isEqualTo(
      tempDir.resolve("suwayomi").resolve("data").resolve("downloads").toAbsolutePath().normalize(),
    )
  }

  private fun newWriter(home: String): SuwayomiConfigWriter {
    val properties =
      OnlineProperties().apply {
        suwayomiHome = home
        suwayomiEndpoint = "http://127.0.0.1:25610"
      }
    return SuwayomiConfigWriter(properties, LantaiProperties())
  }
}
