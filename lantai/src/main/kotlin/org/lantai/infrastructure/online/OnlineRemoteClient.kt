package org.lantai.infrastructure.online

interface OnlineRemoteClient {
  val kind: String

  fun available(): Boolean

  fun sources(): List<RemoteSource>

  fun source(sourceId: String): RemoteSource?

  fun search(
    sourceId: String,
    query: String,
    page: Int,
  ): RemoteSearchResult

  fun browse(
    sourceId: String,
    type: String,
    query: String?,
    page: Int,
    filters: List<OnlineFilterState>,
  ): RemoteBrowseResult

  fun filters(sourceId: String): List<OnlineFilter>

  fun manga(mangaId: Long): RemoteManga

  fun chapters(mangaId: Long): List<RemoteChapter>

  fun chapterPages(chapterId: Long): Int

  fun chapterContent(chapterId: Long): RemoteChapterContent?

  fun asset(
    chapterId: Long,
    assetId: String,
  ): RemotePage?

  fun page(
    mangaId: Long,
    chapterIndex: Int,
    page: Int,
  ): RemotePage?

  fun thumbnail(mangaId: Long): ByteArray?

  fun download(
    mangaId: Long,
    chapterIndex: Int,
  ): ByteArray?
}

object OnlineSourceIds {
  const val PLUGIN = "plugin"

  fun pluginKind(pluginId: String): String = "$PLUGIN:$pluginId"

  fun pluginSourceId(
    pluginId: String,
    sourceId: String,
  ): String = "$PLUGIN:$pluginId:$sourceId"

  fun isPlugin(sourceId: String?): Boolean = sourceId != null && sourceId.startsWith("$PLUGIN:")

  fun kindOf(sourceId: String?): String =
    if (isPlugin(sourceId)) sourceId!!.split(':').take(2).joinToString(":") else PLUGIN

  fun remoteSourceId(sourceId: String): String = if (isPlugin(sourceId)) sourceId.split(':', limit = 3).getOrElse(2) { sourceId } else sourceId
}
