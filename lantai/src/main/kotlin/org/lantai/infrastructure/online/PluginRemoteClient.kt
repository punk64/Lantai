package org.lantai.infrastructure.online

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.lantai.infrastructure.plugin.CapabilityCall
import org.lantai.infrastructure.plugin.PluginHttpClient
import org.lantai.infrastructure.plugin.PluginInstanceService
import java.net.URLEncoder

class PluginRemoteClient(
  override val kind: String,
  private val pluginId: String,
  private val instances: PluginInstanceService,
  private val httpClient: PluginHttpClient,
  private val mapper: ObjectMapper,
) : OnlineRemoteClient {
  override fun available(): Boolean = runCatching { instances.prepare(pluginId) }.isSuccess

  override fun sources(): List<RemoteSource> =
    readList("/v1/source/sources", object : TypeReference<List<RemoteSource>>() {})
      .map { it.copy(id = OnlineSourceIds.pluginSourceId(pluginId, it.id)) }

  override fun source(sourceId: String): RemoteSource? = sources().firstOrNull { it.id == sourceId }

  override fun search(
    sourceId: String,
    query: String,
    page: Int,
  ): RemoteSearchResult {
    val remote = OnlineSourceIds.remoteSourceId(sourceId)
    val parsed = read("/v1/source/sources/${encode(remote)}/search?q=${encode(query)}&page=$page", RemoteSearchResult::class.java)
    return parsed.copy(mangaList = parsed.mangaList?.map { it.prefixed(remote) })
  }

  override fun browse(
    sourceId: String,
    type: String,
    query: String?,
    page: Int,
    filters: List<OnlineFilterState>,
  ): RemoteBrowseResult {
    val remote = OnlineSourceIds.remoteSourceId(sourceId)
    val queryPart = if (query.isNullOrBlank()) "" else "&q=${encode(query)}"
    val parsed =
      read(
        "/v1/source/sources/${encode(remote)}/browse?type=${encode(type)}&page=$page$queryPart",
        RemoteBrowseResult::class.java,
      )
    return parsed.copy(mangas = parsed.mangas.map { it.prefixed(remote) })
  }

  override fun filters(sourceId: String): List<OnlineFilter> =
    readList(
      "/v1/source/sources/${encode(OnlineSourceIds.remoteSourceId(sourceId))}/filters",
      object : TypeReference<List<OnlineFilter>>() {},
    )

  override fun manga(mangaId: Long): RemoteManga = read("/v1/source/manga/$mangaId", RemoteManga::class.java)

  override fun chapters(mangaId: Long): List<RemoteChapter> =
    readList("/v1/source/manga/$mangaId/chapters", object : TypeReference<List<RemoteChapter>>() {})

  override fun chapterPages(chapterId: Long): Int =
    mapper.readTree(json("/v1/source/chapters/$chapterId/pages")).path("pageCount").asInt(-1)

  override fun chapterContent(chapterId: Long): RemoteChapterContent? {
    val result = call("/v1/source/chapters/$chapterId/content", CONTENT_TIMEOUT_SECONDS, CONTENT_MAX_BYTES)
    if (!result.ok) {
      if (result.status == 404) return null
      throw OnlineException(result.message ?: "插件 $pluginId 正文读取失败")
    }
    val body = result.body ?: return null
    if (body.isEmpty()) return null
    return mapper.readValue(body, RemoteChapterContent::class.java)
  }

  override fun asset(
    chapterId: Long,
    assetId: String,
  ): RemotePage? {
    val call = call("/v1/source/chapters/$chapterId/assets/${encode(assetId)}", PAGE_TIMEOUT_SECONDS, PAGE_MAX_BYTES)
    if (!call.ok) {
      if (call.status == 404) return null
      throw OnlineException(call.message ?: "插件 $pluginId 图片读取失败")
    }
    val bytes = call.body ?: return null
    if (bytes.isEmpty()) return null
    return RemotePage(bytes, call.contentType ?: "image/jpeg")
  }

  override fun page(
    mangaId: Long,
    chapterIndex: Int,
    page: Int,
  ): RemotePage? {
    val call = call("/v1/source/manga/$mangaId/chapters/$chapterIndex/pages/$page", PAGE_TIMEOUT_SECONDS, PAGE_MAX_BYTES)
    if (!call.ok) {
      if (call.status == 404) return null
      throw OnlineException(call.message ?: "插件 $pluginId 页图读取失败")
    }
    val bytes = call.body ?: return null
    if (bytes.isEmpty()) return null
    return RemotePage(bytes, call.contentType ?: "image/jpeg")
  }

  override fun thumbnail(mangaId: Long): ByteArray? {
    val call = call("/v1/source/manga/$mangaId/thumbnail", PAGE_TIMEOUT_SECONDS, PAGE_MAX_BYTES)
    if (!call.ok) {
      if (call.status == 404) return null
      throw OnlineException(call.message ?: "插件 $pluginId 封面读取失败")
    }
    return call.body?.takeIf { it.isNotEmpty() }
  }

  override fun download(
    mangaId: Long,
    chapterIndex: Int,
  ): ByteArray? {
    val call = call("/v1/source/manga/$mangaId/chapters/$chapterIndex/download", DOWNLOAD_TIMEOUT_SECONDS, DOWNLOAD_MAX_BYTES)
    if (!call.ok) {
      if (call.status == 404) return null
      throw OnlineException(call.message ?: "插件 $pluginId 章节下载失败")
    }
    return call.body?.takeIf { it.isNotEmpty() }
  }

  private fun <T> read(
    path: String,
    type: Class<T>,
  ): T = mapper.readValue(json(path), type)

  private fun <T> readList(
    path: String,
    type: TypeReference<T>,
  ): T = mapper.readValue(json(path), type)

  private fun json(path: String): ByteArray {
    val result = call(path, CALL_TIMEOUT_SECONDS, MAX_RESPONSE_BYTES)
    if (!result.ok) throw OnlineException(result.message ?: "插件 $pluginId 调用失败")
    return result.body ?: ByteArray(0)
  }

  private fun call(
    path: String,
    timeoutSeconds: Int,
    maxResponseBytes: Int,
  ): CapabilityCall {
    val instance = instances.prepare(pluginId)
    val endpoint = instance.endpoint?.takeIf { it.isNotBlank() } ?: throw OnlineException("插件 $pluginId 没有可用的地址")
    val result = httpClient.invokeGet(endpoint, path, instance.hostKey, timeoutSeconds, maxResponseBytes)
    if (!result.ok && result.status != 404) throw OnlineException(result.message ?: "插件 $pluginId 调用失败")
    return result
  }

  private fun RemoteManga.prefixed(remoteSourceId: String): RemoteManga =
    copy(sourceId = OnlineSourceIds.pluginSourceId(pluginId, sourceId ?: remoteSourceId))

  private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

  private companion object {
    private const val CALL_TIMEOUT_SECONDS = 30
    private const val PAGE_TIMEOUT_SECONDS = 20
    private const val CONTENT_TIMEOUT_SECONDS = 30
    private const val DOWNLOAD_TIMEOUT_SECONDS = 300
    private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
    private const val CONTENT_MAX_BYTES = 2 * 1024 * 1024
    private const val PAGE_MAX_BYTES = 16 * 1024 * 1024
    private const val DOWNLOAD_MAX_BYTES = 256 * 1024 * 1024
  }
}
