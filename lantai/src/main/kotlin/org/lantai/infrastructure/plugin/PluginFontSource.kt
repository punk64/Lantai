package org.lantai.infrastructure.plugin

import com.fasterxml.jackson.databind.ObjectMapper
import org.lantai.domain.persistence.PluginStore
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.Resource
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

@Component
class PluginFontSource(
  private val store: PluginStore,
  private val instances: PluginInstanceService,
  private val httpClient: PluginHttpClient,
  private val mapper: ObjectMapper,
) {
  private val cached = ConcurrentHashMap<String, List<Resource>>()

  @Volatile
  private var refreshedAt = 0L

  fun families(): Set<String> = snapshot().keys

  fun resource(
    family: String,
    file: String,
  ): Resource? = snapshot()[family]?.firstOrNull { it.filename == file }

  fun snapshot(): Map<String, List<Resource>> {
    refreshIfStale()
    return cached.toMap()
  }

  fun invalidate() {
    refreshedAt = 0L
    cached.clear()
  }

  private fun refreshIfStale() {
    if (System.currentTimeMillis() - refreshedAt < TTL_MS) return
    synchronized(this) {
      if (System.currentTimeMillis() - refreshedAt < TTL_MS) return
      val collected = LinkedHashMap<String, MutableList<Resource>>()
      var total = 0L
      for (plugin in store.findProviders(CAPABILITY)) {
        val ready = runCatching { instances.prepare(plugin.pluginId) }.getOrNull() ?: continue
        val endpoint = ready.endpoint?.takeIf { it.isNotBlank() } ?: continue
        val listing =
          runCatching {
            httpClient.invokeGet(endpoint, LISTING_PATH, ready.hostKey, LIST_TIMEOUT_SECONDS, LIST_MAX_BYTES)
          }.getOrNull()
        if (listing?.ok != true) continue
        val families = runCatching { mapper.readTree(listing.body) }.getOrNull() ?: continue
        if (!families.isArray) continue
        for (entry in families) {
          val family = entry.path("family").asText("").trim()
          if (!NAME_PATTERN.matches(family)) continue
          val files = entry.path("files")
          if (!files.isArray) continue
          for (fileNode in files) {
            if (total >= TOTAL_MAX_BYTES) break
            val file = fileNode.asText("").trim()
            if (!NAME_PATTERN.matches(file)) continue
            val existing = cached[family]?.firstOrNull { it.filename == file }
            if (existing != null) {
              collected.getOrPut(family) { mutableListOf() }.add(existing)
              total += runCatching { existing.contentLength() }.getOrDefault(0L)
              continue
            }
            val path = "$LISTING_PATH/$family/$file"
            val call =
              runCatching {
                httpClient.invokeGet(endpoint, path, ready.hostKey, FETCH_TIMEOUT_SECONDS, FILE_MAX_BYTES)
              }.getOrNull()
            val bytes = call?.takeIf { it.ok }?.body ?: continue
            if (total + bytes.size > TOTAL_MAX_BYTES) break
            total += bytes.size
            collected.getOrPut(family) { mutableListOf() }.add(NamedResource(bytes, file))
          }
        }
      }
      cached.clear()
      cached.putAll(collected)
      refreshedAt = System.currentTimeMillis()
    }
  }

  private class NamedResource(
    bytes: ByteArray,
    private val name: String,
  ) : ByteArrayResource(bytes) {
    override fun getFilename(): String = name
  }

  private companion object {
    private const val CAPABILITY = "font.provide"
    private const val LISTING_PATH = "/v1/fonts"
    private const val LIST_TIMEOUT_SECONDS = 10
    private const val FETCH_TIMEOUT_SECONDS = 20
    private const val LIST_MAX_BYTES = 64 * 1024
    private const val FILE_MAX_BYTES = 8 * 1024 * 1024
    private const val TOTAL_MAX_BYTES = 32L * 1024 * 1024
    private const val TTL_MS = 5 * 60 * 1000L
    private val NAME_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._ -]{0,63}$")
  }
}
