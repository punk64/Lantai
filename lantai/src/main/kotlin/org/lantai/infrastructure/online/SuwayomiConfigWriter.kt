package org.lantai.infrastructure.online

import io.github.oshai.kotlinlogging.KotlinLogging
import org.lantai.infrastructure.configuration.LantaiProperties
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.SecureRandom
import java.util.Base64
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.writeText

private val logger = KotlinLogging.logger {}
private const val DEFAULT_USERNAME = "lantai"
private const val DEFAULT_EXTENSION_STORE = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.pb"
private const val LEGACY_EXTENSION_STORE = "https://github.com/keiyoushi/extensions/raw/repo/index.pb"

data class SuwayomiCredentials(
  val username: String,
  val password: String,
  val port: Int,
)

@Service
class SuwayomiConfigWriter(
  private val properties: OnlineProperties,
  private val lantaiProperties: LantaiProperties,
) {
  fun home(): Path {
    val configured = properties.suwayomiHome
    if (configured.isNotBlank()) return Paths.get(configured).toAbsolutePath().normalize()
    val downloads = properties.suwayomiDownloadsDir
    if (downloads.isNotBlank()) {
      val path = Paths.get(downloads).toAbsolutePath().normalize()
      val candidate = if (path.fileName?.toString() == "downloads") path.parent?.parent else null
      return candidate ?: path
    }
    val configDir = lantaiProperties.configDir ?: "."
    val parent = Paths.get(configDir).toAbsolutePath().normalize().parent ?: Paths.get(".").toAbsolutePath()
    return parent.resolve("suwayomi")
  }

  fun dataDir(): Path = home().resolve("data")

  fun jarPath(): Path = home().resolve("bin").resolve("Suwayomi-Server.jar")

  fun serverConf(): Path = dataDir().resolve("server.conf")

  fun effectiveDownloadsDir(): Path {
    val configured = properties.suwayomiDownloadsDir
    return if (configured.isNotBlank()) Paths.get(configured).toAbsolutePath().normalize() else dataDir().resolve("downloads")
  }

  fun endpointPort(): Int {
    val raw = properties.suwayomiEndpoint
    val match = Regex(""":(\d+)""").find(raw)
    return match?.groupValues?.get(1)?.toIntOrNull() ?: 25610
  }

  fun ensureConfig(): SuwayomiCredentials {
    val file = serverConf()
    Files.createDirectories(file.parent)
    val existing = if (file.exists()) file.readLines().toMutableList() else mutableListOf()
    val hadLines = existing.isNotEmpty()
    val current = readCredentials(existing)
    val password = current?.password?.takeIf { it.isNotBlank() } ?: generatePassword()
    val port = endpointPort()
    val result = SuwayomiCredentials(current?.username?.takeIf { it.isNotBlank() } ?: DEFAULT_USERNAME, password, port)

    val lines = existing
    upsert(lines, "server.ip", "\"127.0.0.1\"")
    upsert(lines, "server.port", port.toString())
    upsert(lines, "server.authMode", "BASIC_AUTH")
    upsert(lines, "server.authUsername", "\"${result.username}\"")
    upsert(lines, "server.authPassword", "\"${result.password}\"")
    upsert(lines, "server.initialOpenInBrowserEnabled", "false")
    upsert(lines, "server.kcefEnabled", "false")
    upsert(lines, "server.webUIFlavor", "WEBUI")
    upsert(lines, "server.webUIChannel", "BUNDLED")
    upsert(lines, "server.downloadAsCbz", "true")
    upsert(lines, "server.downloadsPath", "\"${effectiveDownloadsDir().toString().replace("\\", "/")}\"")
    val storeLine = lines.indexOfFirst { it.trimStart().startsWith("server.extensionStores") }
    if (storeLine < 0 || lines[storeLine].contains("[]") || lines[storeLine].contains(LEGACY_EXTENSION_STORE)) {
      upsert(lines, "server.extensionStores", "[\"$DEFAULT_EXTENSION_STORE\"]")
    }

    file.writeText(lines.joinToString("\n", postfix = "\n"))
    if (!hadLines) logger.info { "Generated Suwayomi config at $file" } else logger.debug { "Updated Suwayomi config at $file" }
    return result
  }

  fun rotatePassword(): SuwayomiCredentials {
    val file = serverConf()
    val lines = if (file.exists()) file.readLines().toMutableList() else mutableListOf()
    val password = generatePassword()
    val username = readCredentials(lines)?.username?.takeIf { it.isNotBlank() } ?: DEFAULT_USERNAME
    upsert(lines, "server.authUsername", "\"$username\"")
    upsert(lines, "server.authPassword", "\"$password\"")
    Files.createDirectories(file.parent)
    file.writeText(lines.joinToString("\n", postfix = "\n"))
    return SuwayomiCredentials(username, password, endpointPort())
  }

  fun readCredentials(): SuwayomiCredentials? {
    val file = serverConf()
    if (!file.exists()) return null
    return readCredentials(file.readLines())
  }

  private fun readCredentials(lines: List<String>): SuwayomiCredentials? {
    val username = value(lines, "server.authUsername")
    val password = value(lines, "server.authPassword")
    if (username == null && password == null) return null
    return SuwayomiCredentials(username.orEmpty(), password.orEmpty(), endpointPort())
  }

  private fun value(
    lines: List<String>,
    key: String,
  ): String? {
    val regex = Regex("""^$key\s*=\s*"([^"]*)"""")
    return lines.firstNotNullOfOrNull { regex.find(it)?.groupValues?.get(1) }
  }

  private fun upsert(
    lines: MutableList<String>,
    key: String,
    value: String,
  ) {
    val regex = Regex("""^$key\s*=\s*(?:"[^"]*"|[^#\n]*?)(\s*#.*)?$""")
    val index = lines.indexOfFirst { regex.containsMatchIn(it) }
    if (index >= 0) {
      val comment = regex.find(lines[index])?.groupValues?.getOrNull(1).orEmpty()
      lines[index] = "$key = $value$comment"
    } else {
      lines.add("$key = $value")
    }
  }

  private fun generatePassword(): String {
    val bytes = ByteArray(24)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
  }
}
