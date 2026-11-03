package org.lantai.infrastructure.online

import org.lantai.domain.persistence.OnlineChapterRepository
import org.lantai.domain.persistence.OnlineMangaRepository
import org.springframework.stereotype.Service

@Service
class SuwayomiSourceProvider(
  private val client: SuwayomiClient,
  private val syncService: OnlineSyncService,
  private val readService: OnlineReadService,
  private val downloadService: OnlineDownloadService,
  private val onlineMangaRepository: OnlineMangaRepository,
  private val onlineChapterRepository: OnlineChapterRepository,
) : OnlineSourceProvider {
  override val kind: String = KIND

  override fun available(): Boolean = client.isEnabled()

  override fun sources(): List<OnlineSourceInfo> =
    syncService
      .sources()
      .filter { it.id != "0" }
      .map { OnlineSourceInfo(id = it.id, name = it.name, lang = it.lang.orEmpty()) }

  override fun search(
    query: String,
    sourceId: String?,
    page: Int,
  ): OnlineMangaPage = syncService.search(query, sourceId, page).toPage()

  override fun browse(
    sourceId: String,
    type: String,
    query: String?,
    page: Int,
    filters: List<SuwayomiFilterState>,
  ): OnlineMangaPage = syncService.browse(sourceId, type, query, page, filters).toPage()

  override fun filters(sourceId: String): List<SuwayomiFilter> = syncService.filters(sourceId)

  override fun subscribe(
    sourceId: String,
    mangaId: Long,
  ): OnlineSubscribeResult = syncService.subscribe(sourceId, mangaId)

  override fun sync(seriesId: String): OnlineSyncResult = syncService.sync(seriesId)

  override fun unsubscribe(
    seriesId: String,
    purgeDownloads: Boolean,
  ) = syncService.unsubscribe(seriesId, purgeDownloads)

  override fun subscriptions(): List<OnlineSubscriptionInfo> =
    syncService.listSubscriptions().map {
      OnlineSubscriptionInfo(
        seriesId = it.seriesId,
        remoteMangaId = it.remoteMangaId,
        title = it.title,
        sourceKind = it.sourceKind,
        status = it.status,
        lastSyncAt = it.lastSyncAt,
      )
    }

  override fun download(bookId: String): OnlineDownloadResult = downloadService.download(bookId)

  override fun chapters(seriesId: String): List<OnlineChapterInfo> {
    val manga = onlineMangaRepository.findBySeriesIdOrNull(seriesId) ?: return emptyList()
    return onlineChapterRepository
      .findAllByMangaId(manga.id)
      .sortedBy { it.remoteChapterIndex }
      .map { OnlineChapterInfo(it.bookId, it.title, it.remoteChapterIndex, it.pageCount, it.downloaded) }
  }

  override fun thumbnail(mangaId: Long): ByteArray? = client.thumbnail(mangaId)

  override fun page(
    bookId: String,
    number: Int,
  ): OnlinePage? = readService.pageOrNull(bookId, number)

  companion object {
    const val KIND = "suwayomi"
  }
}

private fun SuwayomiManga.toInfo() =
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

private fun SuwayomiBrowseResult.toPage() = OnlineMangaPage(mangas.map { it.toInfo() }, hasNextPage)
