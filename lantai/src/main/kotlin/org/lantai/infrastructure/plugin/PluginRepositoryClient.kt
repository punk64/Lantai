package org.lantai.infrastructure.plugin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.f4b6a3.tsid.TsidCreator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.lantai.infrastructure.net.OutboundUrlGuard
import org.springframework.stereotype.Service
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import kotlin.io.path.deleteIfExists
import kotlin.io.path.outputStream

private val logger = KotlinLogging.logger {}

@Service
class PluginRepositoryClient(
  private val paths: PluginPaths,
) {
  private val mapper = ObjectMapper()
  private val http =
    HttpClient
      .newBuilder()
      .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
      .followRedirects(HttpClient.Redirect.NEVER)
      .build()

  fun fetchIndex(indexUrl: String): PluginIndex {
    val base = guard(indexUrl)
    val response = open(base)
    val bytes = response.body().use { readLimited(it, MAX_INDEX_BYTES) }
    val entries = parseIndex(bytes, base)
    logger.info { "Fetched plugin index $base with ${entries.size} entries" }
    return PluginIndex(source = base.toString(), entries = entries)
  }

  fun download(
    entry: PluginIndexEntry,
    onProgress: ((Int, String) -> Unit)? = null,
  ): Path {
    val uri = guard(entry.downloadUrl)
    val dir = paths.downloadDir()
    Files.createDirectories(dir)
    val target = dir.resolve("${entry.pluginId}-${entry.version}-${TsidCreator.getTsid256().toString().lowercase()}.zip")
    val response = open(uri)
    val expected = response.headers().firstValue("Content-Length").orElse("").toLongOrNull() ?: 0L
    val digest = MessageDigest.getInstance("SHA-256")
    var total = 0L
    var mark = 0L
    try {
      response.body().use { input ->
        target.outputStream().use { output ->
          val buffer = ByteArray(BUFFER_BYTES)
          while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_PACKAGE_BYTES) throw PluginRequestException("插件包超过上限 ${MAX_PACKAGE_BYTES / 1024 / 1024} MB")
            digest.update(buffer, 0, read)
            output.write(buffer, 0, read)
            if (onProgress != null && (total - mark >= PROGRESS_STEP || mark == 0L)) {
              mark = total
              val pct = if (expected > 0) (total * 100 / expected).toInt().coerceIn(0, 99) else -1
              onProgress(pct, "正在下载 " + sizeText(total) + if (expected > 0) " / " + sizeText(expected) else "")
            }
          }
        }
      }
      val actual = HexFormat.of().formatHex(digest.digest())
      if (!actual.equals(entry.sha256, ignoreCase = true)) throw PluginRequestException("插件包 sha256 校验不通过")
      onProgress?.invoke(100, "下载完成，正在校验")
      logger.info { "Downloaded plugin ${entry.pluginId} ${entry.version} ($total bytes) from $uri" }
      return target
    } catch (ex: Throwable) {
      target.deleteIfExists()
      throw ex
    }
  }

  internal fun parseIndex(
    bytes: ByteArray,
    base: URI,
  ): List<PluginIndexEntry> {
    val root = runCatching { mapper.readTree(bytes) }.getOrElse { throw PluginRequestException("索引不是合法 JSON") }
    if (root == null || !root.isObject) throw PluginRequestException("索引不是合法 JSON 对象")
    val schema = root.path("schemaVersion").asInt(0)
    if (schema != SUPPORTED_SCHEMA) throw PluginRequestException("索引 schemaVersion 需为 $SUPPORTED_SCHEMA，实际 $schema")
    val array = root.path("plugins")
    if (!array.isArray) throw PluginRequestException("索引缺少 plugins 数组")
    return array.mapNotNull { readEntry(it, base) }.take(MAX_ENTRIES)
  }

  private fun readEntry(
    node: JsonNode,
    base: URI,
  ): PluginIndexEntry? {
    val pluginId = node.path("id").asText("").trim()
    if (!ID_PATTERN.matches(pluginId)) return null
    val version = node.path("version").asText("").trim()
    if (!VERSION_PATTERN.matches(version)) return null
    val protocol = node.path("protocol").asInt(node.path("apiVersion").asInt(0))
    if (protocol !in SUPPORTED_PROTOCOLS) return null
    val raw = node.path("url").asText("").trim()
    if (raw.isEmpty()) return null
    val resolved = runCatching { base.resolve(raw) }.getOrNull() ?: return null
    val download = runCatching { OutboundUrlGuard.parse(resolved.toString()) }.getOrNull() ?: return null
    val sha256 = node.path("sha256").asText("").trim()
    if (!SHA256_PATTERN.matches(sha256)) return null
    return PluginIndexEntry(
      pluginId = pluginId,
      name = node.path("name").asText("").trim().ifEmpty { pluginId },
      version = version,
      protocol = protocol,
      minHost = node.path("minHost").asText("").trim(),
      capabilities = readCapabilityNames(node.path("capabilities")),
      downloadUrl = download.toString(),
      sha256 = sha256.lowercase(),
    )
  }

  private fun guard(raw: String): URI {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) throw PluginRequestException("仓库地址不能为空")
    return runCatching { OutboundUrlGuard.parseStrict(trimmed) }.getOrElse {
      throw PluginRequestException(it.message ?: "仓库地址不合法")
    }
  }

  private fun open(uri: URI): HttpResponse<InputStream> {
    var current = uri
    var hop = 0
    while (true) {
      val request = HttpRequest.newBuilder(current).timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS)).GET().build()
      val response =
        runCatching { http.send(request, HttpResponse.BodyHandlers.ofInputStream()) }.getOrElse {
          throw PluginRequestException("连接仓库失败：${it.message ?: it.javaClass.simpleName}")
        }
      val status = response.statusCode()
      if (status in 200..299) return response
      response.body().close()
      if (status !in 300..399) throw PluginRequestException("仓库返回 HTTP $status")
      if (hop >= MAX_REDIRECTS) throw PluginRequestException("重定向次数超过 $MAX_REDIRECTS")
      val location = response.headers().firstValue("Location").orElse(null) ?: throw PluginRequestException("重定向缺少 Location")
      val next = runCatching { current.resolve(location) }.getOrElse { throw PluginRequestException("重定向地址不合法") }
      current = guard(next.toString())
      hop += 1
    }
  }

  private fun readLimited(
    input: InputStream,
    limit: Int,
  ): ByteArray {
    val buffer = ByteArray(BUFFER_BYTES)
    val out = java.io.ByteArrayOutputStream()
    while (true) {
      val read = input.read(buffer)
      if (read < 0) break
      if (out.size() + read > limit) throw PluginRequestException("索引超过上限 ${limit / 1024} KB")
      out.write(buffer, 0, read)
    }
    return out.toByteArray()
  }

  private companion object {
    private const val CONNECT_TIMEOUT_SECONDS = 10L
    private const val REQUEST_TIMEOUT_SECONDS = 60L
    private const val MAX_REDIRECTS = 3
    private const val BUFFER_BYTES = 64 * 1024
    private const val PROGRESS_STEP = 1024L * 1024
    private const val MAX_INDEX_BYTES = 2 * 1024 * 1024
    private const val MAX_PACKAGE_BYTES = 64L * 1024 * 1024
    private const val MAX_ENTRIES = 500
    private const val SUPPORTED_SCHEMA = 2
    private val SUPPORTED_PROTOCOLS = setOf(1, 2)
    private val ID_PATTERN = Regex("^[a-z0-9]+([._-][a-z0-9]+)+$")
    private val VERSION_PATTERN = Regex("^[0-9A-Za-z][0-9A-Za-z._-]*$")
    private val SHA256_PATTERN = Regex("^[0-9a-fA-F]{64}$")
  }
}

data class PluginIndex(
  val source: String,
  val entries: List<PluginIndexEntry>,
)

private fun sizeText(bytes: Long): String =
  when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
  }

data class PluginIndexEntry(
  val pluginId: String,
  val name: String,
  val version: String,
  val protocol: Int,
  val minHost: String,
  val capabilities: List<String>,
  val downloadUrl: String,
  val sha256: String,
)
