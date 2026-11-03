package org.lantai.infrastructure.online

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SuwayomiLogSourceTest {
  @Test
  fun `plain suwayomi line is parsed into an entry`() {
    val entry = SuwayomiLogSource.parseLine("10:29:21.590 [main] INFO suwayomi.tachidesk.server.ServerSetup -- Running Suwayomi-Server v2.4.2366")
    assertThat(entry).isNotNull
    assertThat(entry!!.time).isEqualTo("10:29:21.590")
    assertThat(entry.level).isEqualTo("INFO")
    assertThat(entry.thread).isEqualTo("main")
    assertThat(entry.loggerName).isEqualTo("suwayomi.tachidesk.server.ServerSetup")
    assertThat(entry.message).isEqualTo("Running Suwayomi-Server v2.4.2366")
  }

  @Test
  fun `bootstrap lines without a timestamp are treated as continuation`() {
    assertThat(SuwayomiLogSource.parseLine("kotlin-logging: initializing... active logger factory: Slf4jLoggerFactory")).isNull()
  }

  @Test
  fun `dashed logger and empty message still parse`() {
    val entry = SuwayomiLogSource.parseLine("10:29:22.159 [main] INFO Migration --")
    assertThat(entry).isNotNull
    assertThat(entry!!.message).isEmpty()
  }
}
