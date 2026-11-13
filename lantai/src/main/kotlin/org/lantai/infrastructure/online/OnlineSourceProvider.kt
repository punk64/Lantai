package org.lantai.infrastructure.online

import java.time.LocalDateTime

data class OnlineSourceInfo(
  val id: String,
  val name: String,
  val lang: String,
  val enabled: Boolean = true,
)

data class OnlineMangaInfo(
  val mangaId: Long,
  val sourceId: String,
  val title: String,
  val author: String?,
  val artist: String?,
  val description: String?,
  val genres: List<String>,
  val status: String?,
  val thumbnailUrl: String?,
  val inLibrary: Boolean,
)

data class OnlineSubscriptionInfo(
  val seriesId: String,
  val remoteMangaId: String,
  val title: String,
  val sourceKind: String,
  val status: String?,
  val lastSyncAt: LocalDateTime?,
)

data class OnlineChapterInfo(
  val bookId: String,
  val title: String,
  val index: Int,
  val pageCount: Int,
  val downloaded: Boolean,
)

data class OnlineMangaPage(
  val items: List<OnlineMangaInfo>,
  val hasNextPage: Boolean = false,
)

interface OnlineSourceProvider {
  val kind: String

  val supportsDownload: Boolean get() = true

  fun available(): Boolean

  fun status(): OnlineSourceStatus

  fun hiddenKinds(): Set<String> = emptySet()

  fun sources(): List<OnlineSourceInfo>

  fun search(
    query: String,
    sourceId: String?,
    page: Int,
  ): OnlineMangaPage

  fun browse(
    sourceId: String,
    type: String,
    query: String?,
    page: Int,
    filters: List<OnlineFilterState> = emptyList(),
  ): OnlineMangaPage

  fun filters(sourceId: String): List<OnlineFilter>

  fun subscribe(
    sourceId: String,
    mangaId: Long,
  ): OnlineSubscribeResult

  fun sync(seriesId: String): OnlineSyncResult

  fun unsubscribe(
    seriesId: String,
    purgeDownloads: Boolean = false,
  )

  fun subscriptions(): List<OnlineSubscriptionInfo>

  fun chapters(seriesId: String): List<OnlineChapterInfo>

  fun download(bookId: String): OnlineDownloadResult

  fun manga(
    mangaId: Long,
    sourceId: String? = null,
  ): RemoteManga

  fun thumbnail(
    mangaId: Long,
    sourceId: String? = null,
  ): ByteArray?

  fun preferences(sourceId: String): List<OnlinePreference> = emptyList()

  fun updatePreference(
    sourceId: String,
    position: Int,
    change: Map<String, Any>,
  ): List<OnlinePreference> = throw OnlineException("该源不支持阅读器偏好设置")

  fun page(
    bookId: String,
    number: Int,
  ): OnlinePage?
}
