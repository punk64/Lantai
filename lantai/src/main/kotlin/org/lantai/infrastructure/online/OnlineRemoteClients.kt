package org.lantai.infrastructure.online

import com.fasterxml.jackson.databind.ObjectMapper
import org.lantai.domain.persistence.PluginStore
import org.lantai.infrastructure.plugin.PluginHttpClient
import org.lantai.infrastructure.plugin.PluginInstanceService
import org.springframework.stereotype.Component

@Component
class OnlineRemoteClients(
  private val properties: OnlineProperties,
  private val plugins: PluginRemoteClientFactory,
) {
  fun defaultKind(): String = properties.sourceKind

  fun of(kind: String?): OnlineRemoteClient {
    val normalized = kind?.trim()?.takeIf { it.isNotEmpty() } ?: properties.sourceKind
    if (!normalized.startsWith("${OnlineSourceIds.PLUGIN}:")) throw OnlineException("源标识不合法：$normalized")
    return plugins.of(normalized)
  }

  fun ofSource(sourceId: String?): OnlineRemoteClient = of(OnlineSourceIds.kindOf(sourceId))
}

@Component
class PluginRemoteClientFactory(
  private val store: PluginStore,
  private val instances: PluginInstanceService,
  private val httpClient: PluginHttpClient,
  private val mapper: ObjectMapper,
) {
  fun of(kind: String): OnlineRemoteClient {
    val pluginId = kind.removePrefix("${OnlineSourceIds.PLUGIN}:").trim()
    if (pluginId.isEmpty()) throw OnlineException("源标识不合法：$kind")
    if (store.findProviders(SOURCE_CAPABILITY).none { it.pluginId == pluginId }) {
      throw OnlineException("插件 $pluginId 未获得源提供能力授权")
    }
    return PluginRemoteClient(OnlineSourceIds.pluginKind(pluginId), pluginId, instances, httpClient, mapper)
  }

  private companion object {
    private const val SOURCE_CAPABILITY = "source.provide"
  }
}
