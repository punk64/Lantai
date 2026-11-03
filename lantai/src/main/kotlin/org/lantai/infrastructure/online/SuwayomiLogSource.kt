package org.lantai.infrastructure.online

import org.lantai.domain.service.ExtraLogSource
import org.lantai.domain.service.LogEntry
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

@Component
class SuwayomiLogSource(
  private val configWriter: SuwayomiConfigWriter,
) : ExtraLogSource {
  override val sourceId: String = "suwayomi"
  override val displayName: String = "suwayomi.log"

  override fun path(): Path? =
    runCatching {
      configWriter.dataDir().resolve("logs").resolve("lantai-launch.log").takeIf { Files.isRegularFile(it) }
    }.getOrNull()

  override fun parse(line: String): LogEntry? = parseLine(line)

  companion object {
    private val LINE_PATTERN =
      Regex(
        """^(\d{2}:\d{2}:\d{2}[.,]\d{3})\s+\[([^\]]*)\]\s+(\w+)\s+(\S+)\s+--\s?(.*)$""",
      )

    fun parseLine(line: String): LogEntry? {
      val match = LINE_PATTERN.find(line) ?: return null
      val groups = match.groupValues
      return LogEntry(
        time = groups[1],
        level = groups[3],
        thread = groups[2].trim(),
        loggerName = groups[4],
        message = groups[5],
      )
    }
  }
}
