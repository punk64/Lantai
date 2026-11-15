package org.lantai.infrastructure.plugin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

data class PluginIconSpec(
  val kind: String,
  val name: String? = null,
  val path: String? = null,
  val text: String? = null,
  val color: String? = null,
  val weight: Int? = null,
  val scale: Double? = null,
  val fit: String? = null,
  val shape: String? = null,
  val alt: String? = null,
)

object PluginIconSpecs {
  const val KIND_BUILTIN = "builtin"
  const val KIND_FILE = "file"
  const val KIND_TEXT = "text"
  const val MAX_NAME = 32
  const val MAX_PATH = 128
  const val MAX_TEXT_CODE_POINTS = 8
  const val MAX_ALT = 64
  const val MAX_FILE_BYTES = 512L * 1024
  val EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "avif", "svg")
  private val NAME_PATTERN = Regex("^[a-z0-9][a-z0-9-]{0,31}$")
  private val COLOR_PATTERN =
    Regex("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$|^rgba?\\([0-9.,%\\s]{3,40}\\)$")
  private val FITS = setOf("contain", "cover")
  private val SHAPES = setOf("none", "square", "circle")
  private val WEIGHTS = setOf(400, 500, 600, 700)
  private val mapper = ObjectMapper()

  fun parse(
    node: JsonNode?,
    fallbackName: String? = null,
  ): PluginIconSpec? {
    if (node == null || node.isMissingNode || node.isNull) return fallback(fallbackName)
    if (node.isTextual) return parseText(node.asText(""), fallbackName)
    if (!node.isObject) return fallback(fallbackName)
    val type = node.path("type").asText("").trim().lowercase()
    val kind =
      when {
        type.isNotEmpty() -> type
        node.path("path").asText("").isNotBlank() -> KIND_FILE
        node.path("name").asText("").isNotBlank() -> KIND_BUILTIN
        node.path("text").asText("").isNotBlank() || node.path("char").asText("").isNotBlank() -> KIND_TEXT
        else -> return fallback(fallbackName)
      }
    val fit = node.path("fit").asText("").trim().lowercase().takeIf { it in FITS }
    val shape = node.path("shape").asText("").trim().lowercase().takeIf { it in SHAPES }
    val alt = node.path("alt").asText("").trim().takeIf { it.isNotEmpty() }?.take(MAX_ALT)
    return when (kind) {
      KIND_BUILTIN -> {
        val name = node.path("name").asText("").trim()
        if (!NAME_PATTERN.matches(name)) fallback(fallbackName) else PluginIconSpec(KIND_BUILTIN, name = name, fit = fit, shape = shape, alt = alt)
      }
      KIND_FILE -> {
        val path = normalizePath(node.path("path").asText(""))
        if (path == null) fallback(fallbackName) else PluginIconSpec(KIND_FILE, path = path, fit = fit, shape = shape, alt = alt)
      }
      KIND_TEXT -> {
        val text = node.path("text").asText(node.path("char").asText("")).trim()
        if (!textAllowed(text)) {
          fallback(fallbackName)
        } else {
          PluginIconSpec(
            KIND_TEXT,
            text = text,
            color = node.path("color").asText("").trim().takeIf { COLOR_PATTERN.matches(it) },
            weight = node.path("weight").asInt(0).takeIf { it in WEIGHTS },
            scale = node.path("scale").asDouble(0.0).takeIf { it in 0.6..1.2 },
            fit = fit,
            shape = shape,
            alt = alt,
          )
        }
      }
      else -> fallback(fallbackName)
    }
  }

  fun validate(
    node: JsonNode?,
    field: String,
    fileExists: ((String) -> Boolean)? = null,
  ): List<PluginContractIssue> {
    if (node == null || node.isMissingNode || node.isNull) return emptyList()
    if (!node.isTextual && !node.isObject) {
      return listOf(PluginContractIssue("$field.shape", "$field 必须是字符串或对象"))
    }
    val spec =
      parse(node)
        ?: return listOf(
          PluginContractIssue("$field.value", "$field 无法识别：只接受内置图标名、包内 web/ 图片路径，或不超过 8 个字符的文本"),
        )
    val issues = mutableListOf<PluginContractIssue>()
    if (spec.kind == KIND_FILE && fileExists != null && !fileExists("web/" + spec.path)) {
      issues += PluginContractIssue("$field.missing", "$field 指向的文件不在插件包 web/ 内：${spec.path}")
    }
    if (node.isObject) {
      val fit = node.path("fit").asText("").trim()
      if (fit.isNotEmpty() && fit.lowercase() !in FITS) issues += PluginContractIssue("$field.fit", "$field.fit 只接受 contain/cover：$fit")
      val shape = node.path("shape").asText("").trim()
      if (shape.isNotEmpty() && shape.lowercase() !in SHAPES) issues += PluginContractIssue("$field.shape", "$field.shape 只接受 none/square/circle：$shape")
      if (node.has("weight")) {
        val weight = node.path("weight").asInt(0)
        if (weight !in WEIGHTS) issues += PluginContractIssue("$field.weight", "$field.weight 只接受 400/500/600/700：$weight")
      }
      if (node.has("scale")) {
        val scale = node.path("scale").asDouble(0.0)
        if (scale !in 0.6..1.2) issues += PluginContractIssue("$field.scale", "$field.scale 必须在 0.6~1.2 之间：$scale")
      }
      val color = node.path("color").asText("").trim()
      if (color.isNotEmpty() && !COLOR_PATTERN.matches(color)) {
        issues += PluginContractIssue("$field.color", "$field.color 只接受 #hex 或 rgb()/rgba()：$color")
      }
    }
    return issues
  }

  fun toNode(
    spec: PluginIconSpec?,
    srcBuilder: ((String) -> String?)? = null,
  ): ObjectNode? {
    if (spec == null) return null
    val node = mapper.createObjectNode()
    when (spec.kind) {
      KIND_BUILTIN -> {
        node.put("kind", KIND_BUILTIN)
        node.put("name", spec.name)
      }
      KIND_FILE -> {
        val src = spec.path?.let { srcBuilder?.invoke(it) } ?: return null
        node.put("kind", KIND_FILE)
        node.put("src", src)
      }
      KIND_TEXT -> {
        node.put("kind", KIND_TEXT)
        node.put("text", spec.text)
        spec.color?.let { node.put("color", it) }
        spec.weight?.let { node.put("weight", it) }
        spec.scale?.let { node.put("scale", it) }
      }
      else -> return null
    }
    spec.fit?.let { node.put("fit", it) }
    spec.shape?.let { node.put("shape", it) }
    spec.alt?.let { node.put("alt", it) }
    return node
  }

  fun builtinName(spec: PluginIconSpec?): String? =
    spec?.takeIf { it.kind == KIND_BUILTIN }?.name

  fun sanitizeFile(raw: String): String? = normalizePath(raw)

  private fun parseText(
    raw: String,
    fallbackName: String?,
  ): PluginIconSpec? {
    val text = raw.trim()
    if (text.isEmpty()) return fallback(fallbackName)
    if (isFileRef(text)) {
      return normalizePath(text)?.let { PluginIconSpec(KIND_FILE, path = it) } ?: fallback(fallbackName)
    }
    if (NAME_PATTERN.matches(text)) return PluginIconSpec(KIND_BUILTIN, name = text)
    if (textAllowed(text)) return PluginIconSpec(KIND_TEXT, text = text)
    return fallback(fallbackName)
  }

  private fun isFileRef(value: String): Boolean {
    if (value.contains('/')) return true
    val ext = value.substringAfterLast('.', "").lowercase()
    return ext in EXTENSIONS
  }

  private fun normalizePath(raw: String): String? {
    val value = raw.trim().replace('\\', '/')
    if (value.isEmpty() || value.length > MAX_PATH) return null
    if (value.startsWith("/") || value.contains("..") || value.contains(':')) return null
    if (value.any { it.code < 0x20 }) return null
    val segments = value.split('/')
    if (segments.any { it.isEmpty() || it == "." }) return null
    if (value.substringAfterLast('.', "").lowercase() !in EXTENSIONS) return null
    return segments.joinToString("/")
  }

  private fun textAllowed(text: String): Boolean =
    text.isNotEmpty() &&
      text.codePoints().count() <= MAX_TEXT_CODE_POINTS.toLong() &&
      text.none { it.code < 0x20 || it.code == 0x7f }

  private fun fallback(name: String?): PluginIconSpec? =
    name?.takeIf { NAME_PATTERN.matches(it) }?.let { PluginIconSpec(KIND_BUILTIN, name = it) }
}
