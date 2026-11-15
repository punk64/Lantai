package org.lantai.infrastructure.plugin

import com.fasterxml.jackson.databind.ObjectMapper
import org.lantai.domain.model.PluginInstance
import org.lantai.domain.persistence.PluginStore
import org.springframework.stereotype.Component

@Component
class PluginSearchExtender(
  private val store: PluginStore,
  private val instances: PluginInstanceService,
  private val httpClient: PluginHttpClient,
  private val mapper: ObjectMapper,
) {
  fun expand(query: String): List<String> {
    val trimmed = query.trim()
    if (trimmed.isEmpty() || trimmed.length > MAX_QUERY_LENGTH) return emptyList()
    val terms = LinkedHashSet<String>()
    for (plugin in store.findProviders(CAPABILITY)) {
      if (terms.size >= MAX_TERMS) break
      val ready = runCatching { instances.prepare(plugin.pluginId) }.getOrNull() ?: continue
      val endpoint = ready.endpoint?.takeIf { it.isNotBlank() } ?: continue
      val payload = mapper.createObjectNode().put("query", trimmed).put("limit", MAX_TERMS)
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
      for (term in parse(call.body)) {
        val clean = term.trim()
        if (clean.isEmpty() || clean.length > MAX_TERM_LENGTH) continue
        if (clean.equals(trimmed, ignoreCase = true)) continue
        terms.add(clean)
        if (terms.size >= MAX_TERMS) break
      }
    }
    return terms.toList()
  }

  private fun pathOf(instance: PluginInstance): String {
    val declared =
      runCatching { mapper.readTree(instance.manifest).path("capabilities") }
        .getOrNull()
        ?.let { readCapabilityEndpoint(it, CAPABILITY) }
    return declared ?: "/v1/capabilities/$CAPABILITY"
  }

  private fun parse(bytes: ByteArray?): List<String> {
    val root = runCatching { mapper.readTree(bytes) }.getOrNull() ?: return emptyList()
    val items = if (root.isArray) root else root.path("terms")
    if (!items.isArray) return emptyList()
    return items.mapNotNull { it.asText("").trim().takeIf { text -> text.isNotEmpty() } }
  }

  private companion object {
    private const val CAPABILITY = "search.extend"
    private const val TIMEOUT_SECONDS = 10
    private const val MAX_BYTES = 64 * 1024
    private const val MAX_QUERY_LENGTH = 200
    private const val MAX_TERM_LENGTH = 64
    private const val MAX_TERMS = 3
  }
}
