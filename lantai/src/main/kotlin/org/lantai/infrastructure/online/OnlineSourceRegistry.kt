package org.lantai.infrastructure.online

import org.lantai.domain.persistence.OnlineChapterRepository
import org.lantai.domain.persistence.OnlineMangaRepository
import org.lantai.infrastructure.configuration.LantaiSettingsProvider
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service

@Service
class OnlineSourceRegistry(
  private val providers: List<OnlineSourceProvider>,
  private val properties: OnlineProperties,
  private val settingsProvider: LantaiSettingsProvider,
  private val onlineMangaRepository: OnlineMangaRepository,
  private val onlineChapterRepository: OnlineChapterRepository,
) {
  private val byKind = providers.associateBy { it.kind }

  fun default(): OnlineSourceProvider =
    byKind[properties.sourceKind] ?: providers.firstOrNull() ?: throw OnlineException("No online source provider is registered")

  fun providerFor(sourceId: String?): OnlineSourceProvider =
    if (OnlineSourceIds.isPlugin(sourceId)) byKind[OnlineSourceIds.PLUGIN] ?: default() else default()

  fun providerForSeries(seriesId: String): OnlineSourceProvider {
    val kind = onlineMangaRepository.findBySeriesIdOrNull(seriesId)?.sourceKind
    return if (kind.isNullOrBlank()) default() else byKind[kind] ?: default()
  }

  fun providerForBook(bookId: String): OnlineSourceProvider {
    val mangaId = onlineChapterRepository.findByBookIdOrNull(bookId)?.mangaId
    val kind = mangaId?.let { onlineMangaRepository.findByIdOrNull(it)?.sourceKind }
    return if (kind.isNullOrBlank()) default() else byKind[kind] ?: default()
  }

  fun search(
    query: String,
    sourceId: String?,
    page: Int,
  ): OnlineMangaPage {
    if (!sourceId.isNullOrBlank()) return providerFor(sourceId).search(query, sourceId, page)
    val pages = providers.mapNotNull { provider -> runCatching { provider.search(query, null, page) }.getOrNull() }
    return OnlineMangaPage(
      items = pages.flatMap { it.items }.distinctBy { it.sourceId to it.mangaId },
      hasNextPage = pages.any { it.hasNextPage },
    )
  }

  fun subscriptions(): List<OnlineSubscriptionInfo> =
    providers.flatMap { provider -> runCatching { provider.subscriptions() }.getOrDefault(emptyList()) }

  fun statuses(): List<OnlineSourceStatus> = providers.map { it.status() }

  fun summary(): OnlineStatusSummary {
    val list = statuses()
    return OnlineStatusSummary(
      ready = list.any { it.ready },
      contentReady = list.any { it.contentVisible },
      providers = list,
    )
  }

  fun summaryAndRefresh(): OnlineStatusSummary {
    refreshVisibility()
    val list = statuses()
    return OnlineStatusSummary(
      ready = list.any { it.ready },
      contentReady = list.any { it.contentVisible },
      providers = list,
    )
  }

  fun refreshVisibility() {
    val hidden =
      buildSet {
        addAll(statuses().filterNot { it.contentVisible }.map { it.kind })
        providers.forEach { addAll(it.hiddenKinds()) }
      }
    OnlineAvailability.update(hidden)
  }

  @EventListener(ApplicationReadyEvent::class)
  fun onApplicationReady() {
    providers.filterIsInstance<PluginSourceProvider>().forEach { runCatching { it.cleanupDeleted() } }
    runCatching { refreshVisibility() }
  }

  fun listSources(): List<OnlineSourceInfo> =
    providers
      .flatMap { provider -> runCatching { provider.sources() }.getOrDefault(emptyList()) }
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
