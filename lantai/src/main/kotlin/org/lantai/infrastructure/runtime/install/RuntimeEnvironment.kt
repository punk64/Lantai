package org.lantai.infrastructure.runtime.install

import io.github.oshai.kotlinlogging.KotlinLogging
import org.lantai.infrastructure.configuration.LantaiProperties
import org.lantai.infrastructure.runtime.RuntimeProperties
import org.springframework.stereotype.Service
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

@Service
class RuntimeEnvironment(
  private val lantaiProperties: LantaiProperties,
  private val runtimeProperties: RuntimeProperties,
) {
  fun platform(): String = System.getProperty("os.name").lowercase()

  fun isWindows(): Boolean = platform().contains("win")

  fun isLinux(): Boolean = platform().contains("linux")

  fun isMusl(): Boolean =
    isLinux() && listOf("/lib/ld-musl-x86_64.so.1", "/lib/ld-musl-aarch64.so.1").any { Files.exists(Paths.get(it)) }

  fun arch(): String {
    val raw = System.getProperty("os.arch").lowercase()
    return when {
      raw.contains("aarch64") || raw.contains("arm64") -> "arm64"
      raw.contains("arm") -> "arm"
      else -> "x86_64"
    }
  }

  fun configDir(): Path =
    lantaiProperties.configDir
      ?.takeIf { it.isNotBlank() }
      ?.let { Paths.get(it).toAbsolutePath().normalize() }
      ?: Paths.get(System.getProperty("user.home"), ".lantai").toAbsolutePath().normalize()

  fun stagingDir(kind: String): Path = configDir().resolve("runtime").resolve(kind).resolve("packages")

  fun packageDir(): Path? =
    runtimeProperties.packageDir
      .takeIf { it.isNotBlank() }
      ?.let { Paths.get(it).toAbsolutePath().normalize() }

  fun writable(path: Path): Boolean =
    runCatching {
      Files.createDirectories(path)
      val probe = Files.createTempFile(path, ".lantai-probe", ".tmp")
      Files.deleteIfExists(probe)
      true
    }.getOrDefault(false)

  fun freeSpace(path: Path): Long =
    runCatching {
      val target = if (Files.exists(path)) path else path.parent ?: path
      Files.getFileStore(target).usableSpace
    }.getOrDefault(0L)

  fun portFree(port: Int): Boolean =
    runCatching {
      Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 800) }
      false
    }.getOrDefault(true)

  fun commandOutput(
    command: String,
    vararg args: String,
  ): String? =
    runCatching {
      val process = ProcessBuilder(command, *args).redirectErrorStream(true).start()
      val text = process.inputStream.bufferedReader().use { it.readText() }
      if (!process.waitFor(20, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return null
      }
      if (process.exitValue() != 0) return null
      text.trim().takeIf { it.isNotBlank() }
    }.onFailure { logger.debug(it) { "Command $command ${args.joinToString(" ")} failed" } }
      .getOrNull()

  fun commandVersion(command: String): String? =
    commandOutput(command, "--version")?.let { output ->
      Regex("""\d+\.\d+(?:\.\d+)?""").find(output)?.value
    }

  fun ldconfigHas(names: List<String>): List<String> {
    if (!isLinux()) return emptyList()
    val dump = commandOutput("ldconfig", "-p") ?: return names
    return names.filterNot { dump.contains(it) }
  }

  fun humanBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
      value /= 1024
      index++
    }
    return "%.1f %s".format(value, units[index])
  }
}
