package org.lantai.infrastructure.security.agent

import jakarta.annotation.PostConstruct
import org.lantai.infrastructure.configuration.LantaiProperties
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

@Component
class AgentTokenProvider(
  private val lantaiProperties: LantaiProperties,
) {
  val location: String by lazy { tokenFile().toString() }

  val token: String by lazy { loadOrCreate() }

  @PostConstruct
  fun initialize() {
    token
  }

  fun matches(candidate: String): Boolean =
    MessageDigest.isEqual(token.toByteArray(Charsets.UTF_8), candidate.toByteArray(Charsets.UTF_8))

  private fun tokenFile(): Path {
    val configured = lantaiProperties.configDir?.takeIf { it.isNotBlank() }
    val dir =
      if (configured != null) {
        Paths.get(configured)
      } else {
        Paths.get(lantaiProperties.database.file).parent ?: Paths.get(".").toAbsolutePath()
      }
    return dir.resolve("agent-token.txt")
  }

  private fun loadOrCreate(): String {
    val file = tokenFile()
    if (Files.exists(file)) {
      val existing = Files.readString(file).trim()
      if (existing.isNotEmpty()) return existing
    }
    val generated = generate()
    file.parent?.let { Files.createDirectories(it) }
    Files.writeString(file, generated + System.lineSeparator())
    return generated
  }

  private fun generate(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
  }
}
