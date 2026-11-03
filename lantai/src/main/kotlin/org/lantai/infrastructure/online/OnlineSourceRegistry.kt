package org.lantai.infrastructure.online

import org.lantai.infrastructure.configuration.LantaiSettingsProvider
import org.springframework.stereotype.Service

@Service
class OnlineSourceRegistry(
  private val providers: List<OnlineSourceProvider>,
  private val properties: OnlineProperties,
  private val settingsProvider: LantaiSettingsProvider,
) {
  private val byKind = providers.associateBy { it.kind }

  fun default(): OnlineSourceProvider =
    byKind[properties.sourceKind] ?: providers.firstOrNull() ?: throw OnlineException("No online source provider is registered")

  fun listSources(): List<OnlineSourceInfo> =
    default()
      .sources()
      .map { it.copy(enabled = isEnabled(it.id)) }
      .sortedBy { it.name }

  fun enabledSourceIds(): Set<String> = listSources().filter { it.enabled }.map { it.id }.toSet()

  fun isEnabled(sourceId: String): Boolean = sourceId !in settingsProvider.onlineDisabledSources

  fun setEnabled(
    sourceId: String,
    enabled: Boolean,
  ) {
    val current = settingsProvider.onlineDisabledSources.toMutableSet()
    if (enabled) current.remove(sourceId) else current.add(sourceId)
    settingsProvider.onlineDisabledSources = current
  }
}
