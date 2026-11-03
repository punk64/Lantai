package org.lantai.infrastructure.calibre

import io.github.oshai.kotlinlogging.KotlinLogging
import org.lantai.infrastructure.configuration.LantaiProperties
import org.lantai.infrastructure.configuration.LantaiSettingsProvider
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.io.path.isRegularFile

private val logger = KotlinLogging.logger {}

data class CalibreStatus(
  val available: Boolean,
  val enabled: Boolean,
  val version: String?,
  val executablePath: String?,
  val installDir: String,
  val installed: Boolean,
  val mode: String,
  val message: String?,
)

@Service
class CalibreRuntime(
  private val properties: CalibreProperties,
  private val lantaiProperties: LantaiProperties,
  private val settingsProvider: LantaiSettingsProvider,
) {
  fun installDir(): Path {
    val configured = properties.installDir
    if (configured.isNotBlank()) return Paths.get(configured).toAbsolutePath().normalize()
    val base = lantaiProperties.configDir?.takeIf { it.isNotBlank() }?.let { Paths.get(it) } ?: Paths.get(".")
    return base.resolve("runtime").resolve("calibre").toAbsolutePath().normalize()
  }

  fun stagedInstallerDir(): Path = installDir().resolve("installers")

  fun isEnabled(): Boolean = settingsProvider.calibreEnabled ?: properties.enabled

  fun setEnabled(value: Boolean) {
    settingsProvider.calibreEnabled = value
  }

  fun arch(): String {
    val raw = System.getProperty("os.arch").lowercase()
    return when {
      raw.contains("aarch64") || raw.contains("arm64") -> "arm64"
      raw.contains("arm") -> "arm"
      else -> "x86_64"
    }
  }

  fun explicitExecutable(): Path? {
    val fromSettings = settingsProvider.calibreExecutablePath
    val candidate = fromSettings.ifBlank { properties.executablePath }.takeIf { it.isNotBlank() } ?: return null
    val path = Paths.get(candidate).toAbsolutePath().normalize()
    return path.takeIf { it.isRegularFile() }
  }

  fun managedExecutable(): Path? {
    val name = if (isWindows()) "ebook-convert.exe" else "ebook-convert"
    val direct = installDir().resolve(name)
    if (direct.isRegularFile()) return direct
    val nested = installDir().resolve("calibre").resolve(name)
    if (nested.isRegularFile()) return nested
    val found =
      runCatching {
        Files.walk(installDir(), 3).use { stream ->
          stream.filter { it.fileName?.toString() == name && it.isRegularFile() }.findFirst().orElse(null)
        }
      }.getOrNull()
    return found
  }

  fun executable(): Path? = explicitExecutable() ?: managedExecutable()

  fun probeVersion(executable: Path): String? =
    runCatching {
      val process =
        ProcessBuilder(executable.toString(), "--version")
          .redirectErrorStream(true)
          .start()
      val output =
        process.inputStream.bufferedReader().use { it.readText() }
      if (!process.waitFor(60, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return null
      }
      if (process.exitValue() != 0) return null
      Regex("""\d+\.\d+(?:\.\d+)?""").find(output)?.value
    }.onFailure { logger.warn(it) { "Failed to probe Calibre at $executable" } }
      .getOrNull()

  fun isAvailable(): Boolean = isEnabled() && executable() != null

  fun status(): CalibreStatus {
    val installDir = installDir()
    val executable = executable()
    val installed = managedExecutable() != null
    val enabled = isEnabled()
    val version = executable?.let { probeVersion(it) }
    val mode = settingsProvider.calibreConvertMode.ifBlank { DEFAULT_MODE }
    val message =
      when {
        !enabled -> "Calibre integration is disabled"
        executable == null -> "Calibre is not installed; MOBI/AZW3/FB2 stay downloadable only"
        version == null -> "ebook-convert was found at $executable but did not report a version"
        else -> null
      }
    return CalibreStatus(
      available = executable != null && version != null,
      enabled = enabled,
      version = version,
      executablePath = executable?.toString(),
      installDir = installDir.toString(),
      installed = installed,
      mode = mode,
      message = message,
    )
  }

  fun requireExecutable(): Path = executable() ?: throw CalibreException("Calibre is not available")

  private fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("win")

  companion object {
    const val DEFAULT_MODE = "on-demand"
    val MODES = setOf("never", "on-demand", "eager")
  }
}
