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

  fun available(): Boolean

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
    filters: List<SuwayomiFilterState> = emptyList(),
  ): OnlineMangaPage

  fun filters(sourceId: String): List<SuwayomiFilter>

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

  fun thumbnail(mangaId: Long): ByteArray?

  fun page(
    bookId: String,
    number: Int,
  ): OnlinePage?
}
