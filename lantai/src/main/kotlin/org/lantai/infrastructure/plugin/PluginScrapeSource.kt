package org.lantai.infrastructure.plugin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.lantai.domain.model.PluginInstance
import org.lantai.domain.model.ScrapeCandidate
import org.lantai.domain.persistence.PluginStore
import org.lantai.infrastructure.metadata.scrape.ScrapeSource
import org.springframework.stereotype.Component

@Component
class PluginScrapeSource(
  private val store: PluginStore,
  private val instances: PluginInstanceService,
  private val httpClient: PluginHttpClient,
  private val mapper: ObjectMapper,
) : ScrapeSource {
  override val id: String = ID

  override val displayName: String = "插件元数据源"

  override fun isEnabled(): Boolean = false

  override fun search(
    query: String,
    limit: Int,
  ): List<ScrapeCandidate> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return emptyList()
    val capped = limit.coerceIn(1, MAX_RESULTS)
    val collected = mutableListOf<ScrapeCandidate>()
    for (plugin in store.findProviders(CAPABILITY)) {
      if (collected.size >= capped) break
      val ready = runCatching { instances.prepare(plugin.pluginId) }.getOrNull() ?: continue
      val endpoint = ready.endpoint?.takeIf { it.isNotBlank() } ?: continue
      val payload = mapper.createObjectNode().put("query", trimmed).put("limit", capped)
      val call =
        runCatching {
          httpClient.invokeCapability(
            endpoint = endpoint,
            path = pathOf(ready),
            hostKey = ready.hostKey,
            payload = mapper.writeValueAsString(payload),
            timeoutSeconds = TIMEOUT_SECONDS,
            maxResponseBytes = MAX_BYTES,
          )
        }.getOrNull()
      if (call?.ok != true) continue
      collected.addAll(parse(call.body, ready.name, ready.pluginId))
    }
    return collected.take(capped)
  }

  private fun pathOf(instance: PluginInstance): String {
    val declared =
      runCatching { mapper.readTree(instance.manifest).path("capabilities") }
        .getOrNull()
        ?.let { readCapabilityEndpoint(it, CAPABILITY) }
    return declared ?: "/v1/capabilities/$CAPABILITY"
  }

  private fun parse(
    bytes: ByteArray?,
    sourceName: String,
    pluginId: String,
  ): List<ScrapeCandidate> {
    val root = runCatching { mapper.readTree(bytes) }.getOrNull() ?: return emptyList()
    val items = if (root.isArray) root else root.path("candidates")
    if (!items.isArray) return emptyList()
    return items.mapNotNull { node ->
      val title = node.path("title").asText("").trim()
      if (title.isEmpty()) return@mapNotNull null
      ScrapeCandidate(
        source = sourceName,
        sourceId = "$ID:$pluginId",
        title = title,
        subtitle = node.path("subtitle").asText("").trim(),
        authors = strings(node.path("authors")),
        publisher = node.path("publisher").asText("").trim(),
        region = node.path("region").asText("").trim(),
        releaseDate = node.path("releaseDate").asText("").trim(),
        summary = node.path("summary").asText("").trim(),
        coverUrl = safeUrl(node.path("coverUrl").asText("").trim()),
        tags = strings(node.path("tags")),
        rating = node.path("rating").takeIf { it.isNumber }?.asDouble(),
        ratingCount = node.path("ratingCount").takeIf { it.isIntegralNumber }?.asInt(),
        url = safeUrl(node.path("url").asText("").trim()),
      )
    }
  }

  private fun strings(node: JsonNode): List<String> {
    if (!node.isArray) return emptyList()
    return node.mapNotNull { it.asText("").trim().takeIf { text -> text.isNotEmpty() } }
  }

  private fun safeUrl(raw: String): String =
    raw.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: ""

  private companion object {
    private const val ID = "plugin-metadata"
    private const val CAPABILITY = "metadata.provide"
    private const val TIMEOUT_SECONDS = 20
    private const val MAX_BYTES = 1024 * 1024
    private const val MAX_RESULTS = 20
  }
}
