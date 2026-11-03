package org.lantai.infrastructure.calibre

import io.github.oshai.kotlinlogging.KotlinLogging
import org.lantai.domain.model.convertedEpubPathFor
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.isRegularFile

private val logger = KotlinLogging.logger {}

@Service
class CalibreConverter(
  private val runtime: CalibreRuntime,
  private val properties: CalibreProperties,
) {
  private val lock = ReentrantLock()

  fun isConverted(source: Path): Boolean = convertedEpubPathFor(source).isRegularFile()

  fun convert(source: Path): Path {
    if (!source.isRegularFile()) throw CalibreException("Source file not found: $source")
    val target = convertedEpubPathFor(source)
    if (target.isRegularFile()) return target
    val executable = runtime.requireExecutable()

    lock.withLock {
      if (target.isRegularFile()) return target
      val logDir = runtime.installDir().resolve("logs")
      Files.createDirectories(logDir)
      val logFile = logDir.resolve("convert.log").toFile()
      logger.info { "Converting $source to $target with $executable" }
      val process =
        ProcessBuilder(executable.toString(), source.toString(), target.toString())
          .redirectErrorStream(true)
          .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
          .start()
      if (!process.waitFor(properties.convertTimeoutSeconds.toLong(), TimeUnit.SECONDS)) {
        process.destroyForcibly()
        Files.deleteIfExists(target)
        throw CalibreException("Conversion of $source timed out after ${properties.convertTimeoutSeconds}s")
      }
      if (process.exitValue() != 0 || !target.isRegularFile()) {
        Files.deleteIfExists(target)
        throw CalibreException("Conversion of $source failed with exit ${process.exitValue()}: ${tail(logFile)}")
      }
      logger.info { "Converted $source to $target" }
      return target
    }
  }

  private fun tail(file: java.io.File): String =
    runCatching { file.readLines().takeLast(10).joinToString(" | ") }.getOrDefault("")
}
