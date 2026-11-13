package org.lantai.infrastructure.plugin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.lantai.domain.model.PluginTaskSpec
import org.springframework.scheduling.support.CronExpression
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

object PluginTaskSpecs {
  const val MAX_TASKS: Int = 8
  const val MAX_NAME_LENGTH: Int = 64
  const val MIN_TIMEOUT_SECONDS: Int = 5
  const val MAX_TIMEOUT_SECONDS: Int = 3600
  const val DEFAULT_TIMEOUT_SECONDS: Int = 300
  const val MAX_ENDPOINT_LENGTH: Int = 256

  private val NAME_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,${MAX_NAME_LENGTH - 1}}")

  private val mapper = ObjectMapper()

  fun parse(manifest: String): List<PluginTaskSpec> {
    if (manifest.isBlank()) return emptyList()
    val node = runCatching { mapper.readTree(manifest).path("tasks") }.getOrNull() ?: return emptyList()
    if (!node.isArray) return emptyList()
    val seen = mutableSetOf<String>()
    val specs = mutableListOf<PluginTaskSpec>()
    node.forEach { element ->
      if (specs.size >= MAX_TASKS) return@forEach
      val spec = read(element) ?: return@forEach
      if (seen.add(spec.name)) specs.add(spec)
    }
    return specs
  }

  fun cronOf(spec: PluginTaskSpec): CronExpression? =
    normalizeCron(spec.cron)?.let { runCatching { CronExpression.parse(it) }.getOrNull() }

  fun dueBetween(
    expression: CronExpression,
    since: Instant,
    now: Instant,
    zone: ZoneId,
  ): Boolean {
    if (!now.isAfter(since)) return false
    var next: ZonedDateTime? = expression.next(since.atZone(zone)) ?: return false
    var fired = false
    while (next != null && !next.toInstant().isAfter(now)) {
      fired = true
      next = expression.next(next)
    }
    return fired
  }

  private fun read(node: JsonNode): PluginTaskSpec? {
    val name = node.path("name").asText("").trim()
    val cron = normalizeCron(node.path("cron").asText("").trim()) ?: return null
    val endpoint = node.path("endpoint").asText("").trim()
    if (!NAME_PATTERN.matches(name)) return null
    if (!endpoint.startsWith("/") || endpoint.length > MAX_ENDPOINT_LENGTH) return null
    if (endpoint.contains("..") || endpoint.contains("://")) return null
    val timeout = node.path("timeoutSeconds").asInt(DEFAULT_TIMEOUT_SECONDS)
    return PluginTaskSpec(
      name = name,
      cron = cron,
      endpoint = endpoint,
      timeoutSeconds = timeout.coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS),
    )
  }

  private fun normalizeCron(raw: String): String? {
    if (raw.isEmpty()) return null
    val normalized =
      when (raw.split(Regex("\\s+")).size) {
        5 -> "0 $raw"
        6 -> raw
        else -> return null
      }
    return normalized.takeIf { runCatching { CronExpression.parse(it) }.isSuccess }
  }
}
