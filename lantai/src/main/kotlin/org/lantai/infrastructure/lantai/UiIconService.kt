package org.lantai.infrastructure.lantai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PostConstruct
import org.lantai.infrastructure.configuration.LantaiProperties
import org.lantai.infrastructure.plugin.PluginIconSpecs
import org.springframework.stereotype.Component
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

data class UiIconFile(
  val bytes: ByteArray,
  val mediaType: String,
  val etag: String,
)

@Component
class UiIconService(
  private val properties: LantaiProperties,
) {
  private val mapper = ObjectMapper()

  val root: Path =
    Path
      .of(properties.configDir?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/.lantai"), "ui")
      .toAbsolutePath()
      .normalize()

  @PostConstruct
  fun prepare() {
    runCatching { Files.createDirectories(root) }
  }

  fun icons(): Map<String, JsonNode> {
    val declared = properties.ui.icons
    if (declared.isEmpty()) return emptyMap()
    val out = linkedMapOf<String, JsonNode>()
    declared.forEach { (key, value) ->
      val name = key.trim().trimStart('/')
      if (name.isEmpty() || name.length > 64) return@forEach
      val spec = PluginIconSpecs.parse(mapper.valueToTree<JsonNode>(value)) ?: return@forEach
      val node = PluginIconSpecs.toNode(spec) { path -> url(path) } ?: return@forEach
      out[name] = node
    }
    return out
  }

  fun read(rawPath: String): UiIconFile? {
    val relative = PluginIconSpecs.sanitizeFile(rawPath) ?: return null
    val file = root.resolve(relative).normalize()
    if (!file.startsWith(root) || !Files.isRegularFile(file)) return null
    val size = runCatching { Files.size(file) }.getOrDefault(0L)
    if (size <= 0L || size > PluginIconSpecs.MAX_FILE_BYTES) return null
    val bytes = runCatching { Files.readAllBytes(file) }.getOrNull() ?: return null
    val modified = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrDefault(0L)
    return UiIconFile(bytes, mediaType(relative), "\"$size-$modified\"")
  }

  private fun url(path: String): String = "/api/lantai/v1/ui/icon?path=" + URLEncoder.encode(path, StandardCharsets.UTF_8)

  private fun mediaType(path: String): String =
    when (path.substringAfterLast('.', "").lowercase()) {
      "png" -> "image/png"
      "jpg", "jpeg" -> "image/jpeg"
      "webp" -> "image/webp"
      "gif" -> "image/gif"
      "avif" -> "image/avif"
      else -> "image/svg+xml"
    }
}
