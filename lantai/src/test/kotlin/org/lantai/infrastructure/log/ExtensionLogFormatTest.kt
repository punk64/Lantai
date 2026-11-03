package org.lantai.infrastructure.log

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.lantai.domain.service.LogFileReader
import org.lantai.infrastructure.configuration.LantaiProperties
import org.springframework.mock.env.MockEnvironment
import java.nio.file.Path

class ExtensionLogFormatTest {
  @TempDir
  lateinit var dir: Path

  @Test
  fun `extension log lines are parsed as entries by the log reader`() {
    val props = LantaiProperties().apply { configDir = dir.toString() }
    val log = ExtensionLog(props)
    log.info("扩展清单加载成功 1200 条，源 https://example/a，106136 字节，耗时 1300ms")
    log.warn("扩展清单拉取失败 https://example/b（15000ms）：ResourceAccessException: Read timed out")
    log.error("扩展清单不可用：所有来源均失败", IllegalStateException("no source reachable"))

    val reader = LogFileReader(MockEnvironment(), props, emptyList())
    val file = reader.listFiles().firstOrNull { it.name == "extensions.log" }
    assertThat(file).isNotNull
    assertThat(file!!.source).isEqualTo("extensions")

    val page = reader.read("extensions.log", 0, 50, null, null, null, null)
    assertThat(page.entries).hasSize(3)
    assertThat(page.entries[0].level).isEqualTo("INFO")
    assertThat(page.entries[0].message).contains("扩展清单加载成功")
    assertThat(page.entries[1].level).isEqualTo("WARN")
    assertThat(page.entries[1].message).contains("Read timed out")
    assertThat(page.entries[2].level).isEqualTo("ERROR")
    assertThat(page.entries[2].message).contains("IllegalStateException")
  }

  @Test
  fun `log file name is stable and rotated file keeps the prefix`() {
    val props = LantaiProperties().apply { configDir = dir.toString() }
    val log = ExtensionLog(props)
    assertThat(log.path().fileName.toString()).isEqualTo("extensions.log")
    assertThat(log.path().startsWith(dir.resolve("logs"))).isTrue()
  }
}
