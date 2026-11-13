package org.lantai.infrastructure.online

import io.github.oshai.kotlinlogging.KotlinLogging
import org.lantai.domain.model.PluginState
import org.lantai.domain.persistence.OnlineChapterRepository
import org.lantai.domain.persistence.OnlineMangaRepository
import org.lantai.domain.persistence.PluginStore
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}
private const val SOURCE_PROVIDE = "source.provide"
private const val SOURCE_CACHE_MILLIS = 60_000L

@Component
class PluginSourceProvider(
  private val clients: OnlineRemoteClients,
  private val syncService: OnlineSyncService,
  private val readService: OnlineReadService,
  private val onlineMangaRepository: OnlineMangaRepository,
  private val onlineChapterRepository: OnlineChapterRepository,
  private val store: PluginStore,
  private val downloadService: OnlineDownloadService,
) : OnlineSourceProvider {
  override val kind: String = OnlineSourceIds.PLUGIN

  override val supportsDownload: Boolean = true

  @Volatile
  private var cachedSources: List<OnlineSourceInfo> = emptyList()

  @Volatile
  private var cachedKey: String = ""

  @Volatile
  private var cachedAt: Long = 0

  override fun available(): Boolean = status().running

  override fun status(): OnlineSourceStatus {
    val providers = store.findProviders(SOURCE_PROVIDE)
    val enabledIds = providers.filter { it.enabled }.map { it.pluginId }.distinct()
    return OnlineSourceStatus(
      kind = kind,
      name = "插件源",
      installed = providers.isNotEmpty(),
      enabled = enabledIds.isNotEmpty(),
      running = providers.any { it.enabled && it.state == PluginState.READY },
    )
  }

  override fun hiddenKinds(): Set<String> {
    val enabled = store.findAllInstances().filter { it.enabled }.map { it.pluginId }.toSet()
    return onlineMangaRepository
      .findAll()
      .map { it.sourceKind }
      .filter { OnlineSourceIds.isPlugin(it) }
      .filterNot { it.removePrefix("${OnlineSourceIds.PLUGIN}:").substringBefore(':') in enabled }
      .toSet()
  }

  fun cleanupDeleted(): Int = syncService.removeByMissingPlugins(store.findAllInstances().map { it.pluginId }.toSet())

  override fun sources(): List<OnlineSourceInfo> {
    val ids = pluginIds()
    val key = ids.joinToString(",")
    val cached = cachedSources
    val now = System.currentTimeMillis()
    if (key == cachedKey && cached.isNotEmpty() && now - cachedAt < SOURCE_CACHE_MILLIS) return cached
    val listed =
      ids
        .flatMap { pluginId ->
          runCatching { clients.of(OnlineSourceIds.pluginKind(pluginId)).sources() }
            .onFailure { logger.warn(it) { "Listing sources of plugin $pluginId failed" } }
            .getOrDefault(emptyList())
        }
        .map { OnlineSourceInfo(id = it.id, name = it.name, lang = it.lang.orEmpty()) }
    cachedSources = listed
    cachedKey = key
    cachedAt = now
    return listed
  }

  override fun search(
    query: String,
    sourceId: String?,
    page: Int,
  ): OnlineMangaPage {
    if (!sourceId.isNullOrBlank()) return syncService.search(query, sourceId, page).toPage()
    val pages =
      sources().map { it.id }.mapNotNull { id ->
        runCatching { syncService.search(query, id, page) }
          .onFailure { logger.warn(it) { "Searching plugin source $id failed" } }
          .getOrNull()
      }
    return OnlineMangaPage(
      items = pages.flatMap { it.mangas }.distinctBy { it.sourceId to it.id }.map { it.toInfo() },
      hasNextPage = pages.any { it.hasNextPage },
    )
  }

  override fun browse(
    sourceId: String,
    type: String,
    query: String?,
    page: Int,
    filters: List<OnlineFilterState>,
  ): OnlineMangaPage = syncService.browse(sourceId, type, query, page, filters).toPage()

  override fun filters(sourceId: String): List<OnlineFilter> = syncService.filters(sourceId)

  override fun subscribe(
    sourceId: String,
    mangaId: Long,
  ): OnlineSubscribeResult = syncService.subscribe(sourceId, mangaId)

  override fun sync(seriesId: String): OnlineSyncResult = syncService.sync(seriesId)

  override fun unsubscribe(
    seriesId: String,
    purgeDownloads: Boolean,
  ) = syncService.unsubscribe(seriesId, purgeDownloads)

  override fun subscriptions(): List<OnlineSubscriptionInfo> {
    val enabledKinds = pluginIds().map { OnlineSourceIds.pluginKind(it) }.toSet()
    return syncService
      .listSubscriptions()
      .filter { OnlineSourceIds.isPlugin(it.sourceKind) && it.sourceKind in enabledKinds }
      .map {
        OnlineSubscriptionInfo(
          seriesId = it.seriesId,
          remoteMangaId = it.remoteMangaId,
          title = it.title,
          sourceKind = it.sourceKind,
          status = it.status,
          lastSyncAt = it.lastSyncAt,
        )
      }
  }

  override fun chapters(seriesId: String): List<OnlineChapterInfo> {
    val manga = onlineMangaRepository.findBySeriesIdOrNull(seriesId) ?: return emptyList()
    return onlineChapterRepository
      .findAllByMangaId(manga.id)
      .sortedBy { it.remoteChapterIndex }
      .map { OnlineChapterInfo(it.bookId, it.title, it.remoteChapterIndex, it.pageCount, it.downloaded) }
  }

  override fun download(bookId: String): OnlineDownloadResult = downloadService.download(bookId)

  override fun manga(
    mangaId: Long,
    sourceId: String?,
  ): RemoteManga {
    if (sourceId.isNullOrBlank()) throw OnlineException("插件源详情必须携带源标识")
    return clients.ofSource(sourceId).manga(mangaId)
  }

  override fun thumbnail(
    mangaId: Long,
    sourceId: String?,
  ): ByteArray? {
    if (sourceId.isNullOrBlank()) throw OnlineException("插件源封面必须携带源标识")
    return clients.ofSource(sourceId).thumbnail(mangaId)
  }

  override fun page(
    bookId: String,
    number: Int,
  ): OnlinePage? = readService.pageOrNull(bookId, number)

  private fun pluginIds(): List<String> =
    store
      .findProviders(SOURCE_PROVIDE)
      .filter { it.enabled }
      .map { it.pluginId }
      .distinct()

  private fun RemoteBrowseResult.toPage(): OnlineMangaPage =
    OnlineMangaPage(
      items = mangas.map { it.toInfo() },
      hasNextPage = hasNextPage,
    )

  private fun RemoteManga.toInfo(): OnlineMangaInfo =
    OnlineMangaInfo(
      mangaId = id,
      sourceId = sourceId.orEmpty(),
      title = title,
      author = author,
      artist = artist,
      description = description,
      genres = genre.orEmpty(),
      status = status,
      thumbnailUrl = thumbnailUrl,
      inLibrary = inLibrary ?: false,
    )
}
