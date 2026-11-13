package org.lantai.infrastructure.plugin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Paths

data class PluginPanelSpec(
  val id: String,
  val title: String,
  val icon: String,
  val group: String,
  val path: String,
  val titleI18n: Map<String, String>,
  val roles: List<String>,
)

object PluginPanelSpecs {
  const val MAX_PANELS: Int = 8
  const val MAX_TEXT_LENGTH: Int = 64
  const val MAX_PATH_LENGTH: Int = 128
  private val ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")
  private val GROUP_KEYS = setOf("system", "library", "reading", "more")
  private val mapper = ObjectMapper()

  fun parse(manifest: String): List<PluginPanelSpec> {
    if (manifest.isBlank()) return emptyList()
    val node = runCatching { mapper.readTree(manifest).path("panels") }.getOrNull() ?: return emptyList()
    if (!node.isArray) return emptyList()
    val seen = mutableSetOf<String>()
    val specs = mutableListOf<PluginPanelSpec>()
    node.forEach { element ->
      if (specs.size >= MAX_PANELS) return@forEach
      val spec = read(element) ?: return@forEach
      if (seen.add(spec.id)) specs.add(spec)
    }
    return specs
  }

  fun sanitizePath(
    raw: String,
    specs: List<PluginPanelSpec>,
  ): String? {
    val decoded =
      runCatching { URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8) }.getOrNull() ?: return null
    if (!decoded.startsWith("/")) return null
    if (decoded.contains('\\') || decoded.contains("..") || decoded.contains("//")) return null
    if (decoded.contains('%') || decoded.contains("://")) return null
    if (decoded.any { it.code < 0x20 }) return null
    val normalized =
      runCatching { Paths.get(decoded).normalize().toString().replace('\\', '/') }.getOrNull() ?: return null
    if (!normalized.startsWith("/") || normalized.contains("..")) return null
    val bases = specs.map { it.path }.ifEmpty { listOf("/") }
    val allowed =
      bases.any { base ->
        if (base == "/") true else normalized == base || normalized.startsWith(base.trimEnd('/') + "/")
      }
    return if (allowed) normalized else null
  }

  private fun read(node: JsonNode): PluginPanelSpec? {
    val id = node.path("id").asText("").trim()
    if (!ID_PATTERN.matches(id)) return null
    val title = node.path("title").asText("").trim()
    if (title.isEmpty() || title.length > MAX_TEXT_LENGTH) return null
    val icon = node.path("icon").asText("plug").trim().take(MAX_TEXT_LENGTH)
    val group = node.path("group").asText("system").trim().takeIf { it in GROUP_KEYS } ?: "system"
    val path = node.path("path").asText("/").trim()
    if (!path.startsWith("/") || path.length > MAX_PATH_LENGTH) return null
    if (path.contains("..") || path.contains("://")) return null
    val roles = node.path("roles").takeIf { it.isArray }
      ?.map { it.asText("").trim().uppercase() }
      ?.filter { it.isNotEmpty() }
      ?.distinct()
      ?: emptyList()
    val i18n = linkedMapOf<String, String>()
    node.path("titleI18n").takeIf { it.isObject }?.properties()?.forEach { (key, value) ->
      if (key.length <= 16 && value.isTextual && value.asText().length <= MAX_TEXT_LENGTH) {
        i18n[key] = value.asText()
      }
    }
    return PluginPanelSpec(id, title, icon, group, path, i18n, roles)
  }
}
