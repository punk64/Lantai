package org.lantai.infrastructure.log

import org.lantai.infrastructure.configuration.LantaiProperties
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

@Service
class ExtensionLog(
  lantaiProperties: LantaiProperties,
) {
  private val dir: Path = Paths.get(lantaiProperties.configDir ?: ".", "logs")
  private val file: Path = dir.resolve("extensions.log")
  private val lock = Any()

  fun info(message: String) = write("INFO", message)

  fun warn(message: String) = write("WARN", message)

  fun error(
    message: String,
    error: Throwable? = null,
  ) = write("ERROR", message + (error?.let { ": ${it.javaClass.simpleName}: ${it.message}" } ?: ""))

  fun path(): Path = file

  private fun write(
    level: String,
    message: String,
  ) {
    val line =
      OffsetDateTime.now().format(TIME_FORMAT) +
        " " + level.padStart(5) +
        " 0 --- [     ext-runner] org.lantai.extensions                 : " +
        message.replace('\n', ' ').replace('\r', ' ') +
        System.lineSeparator()
    synchronized(lock) {
      runCatching {
        Files.createDirectories(dir)
        rotateIfNeeded()
        Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
      }
    }
  }

  private fun rotateIfNeeded() {
    if (!Files.exists(file) || Files.size(file) < MAX_BYTES) return
    Files.move(file, dir.resolve("extensions.1.log"), StandardCopyOption.REPLACE_EXISTING)
  }

  companion object {
    private const val MAX_BYTES = 5L * 1024 * 1024
    private val TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
  }
}
