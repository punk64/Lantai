package org.lantai.infrastructure.upgrade

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.lantai.infrastructure.net.OutboundUrlGuard
import org.lantai.infrastructure.runtime.install.RuntimeEnvironment
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

private val remoteLogger = KotlinLogging.logger {}

private val SAFE_ASSET_NAME = Regex("""^[0-9A-Za-z._-]{1,160}\.zip$""", RegexOption.IGNORE_CASE)
private const val MAX_REDIRECTS = 5

@Service
class RemoteUpgradeService(
  private val runtimeEnvironment: RuntimeEnvironment,
  private val objectMapper: ObjectMapper,
  private val upgradeService: UpgradeService,
  @param:Value("\${lantai.upgrade.feed:https://api.github.com/repos/punk64/Lantai/releases}") private val feed: String,
  @param:Value("\${lantai.upgrade.cache-hours:4}") private val cacheHours: Long,
  @param:Value("\${lantai.upgrade.allowed-hosts:}") allowedHostsRaw: String,
  @param:Value("\${lantai.upgrade.max-download-mb:2048}") private val maxDownloadMb: Long,
) {
  private val root: Path = runtimeEnvironment.configDir().resolve("upgrade")
  private val downloads: Path = root.resolve("downloads")
  private val cacheFile: Path = root.resolve("remote.json")
  private val progressRef = AtomicReference(RemoteProgress(idle = true))
  private val allowedHosts: Set<String> =
    (
      allowedHostsRaw.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() } +
        listOf("github.com", "api.github.com", "objects.githubusercontent.com", "codeload.github.com", "*.githubusercontent.com", "*.github.com") +
        runCatching { URI.create(feed).host?.lowercase() }.getOrNull().orEmpty().let { listOf(it) }
    ).filter { it.isNotEmpty() }.toSet()
  private val client =
    HttpClient
      .newBuilder()
      .connectTimeout(Duration.ofSeconds(15))
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build()
  private val downloadClient =
    HttpClient
      .newBuilder()
      .connectTimeout(Duration.ofSeconds(15))
      .followRedirects(HttpClient.Redirect.NEVER)
      .build()

  fun progress(): RemoteProgress = progressRef.get()

  fun info(refresh: Boolean): RemoteInfo {
    val current = upgradeService.currentVersion
    val cached = readCache()
    if (!refresh && cached != null && fresh(cached)) return build(cached, current, null)
    return try {
      val releases = fetchFeed()
      val doc = RemoteCache(checkedAt = Instant.now().toString(), source = feed, releases = releases)
      writeCache(doc)
      build(doc, current, null)
    } catch (e: Exception) {
      remoteLogger.warn(e) { "Upgrade feed unreachable: $feed" }
      val msg = e.message ?: e.javaClass.simpleName
      if (cached != null) build(cached, current, msg) else build(RemoteCache(Instant.now().toString(), feed, emptyList()), current, msg)
    }
  }

  fun fetch(request: FetchRequest): UpgradeInspect {
    val current = upgradeService.currentVersion
    val latest = info(false).latest ?: throw IllegalStateException("当前没有可用的更新版本")
    val version = request.version?.trim()?.takeIf { it.isNotBlank() } ?: latest.version
    val url = request.assetUrl?.trim()?.takeIf { it.isNotBlank() } ?: latest.assetUrl
    if (url.isNullOrBlank()) throw IllegalStateException("该版本没有可下载的升级包")
    if (compareVersions(version, current) <= 0) throw IllegalStateException("版本 $version 不高于当前版本 $current")

    Files.createDirectories(downloads)
    val name = resolveAssetName(request.assetName, url, version)
    val part = downloads.resolve("$name.part").normalize()
    val target = downloads.resolve(name).normalize()
    if (target.parent != downloads.normalize()) throw IllegalStateException("升级包名称不合法：$name")
    val limit = maxDownloadMb.coerceIn(64, 16384) * 1024 * 1024

    progressRef.set(RemoteProgress(idle = false, phase = "download", pct = 0, label = "0 B"))
    try {
      val res = openDownload(requireAllowedUrl(url))
      if (res.statusCode() !in 200..299) {
        res.body().close()
        throw IllegalStateException("下载失败：HTTP ${res.statusCode()}")
      }
      val total = res.headers().firstValueAsLong("content-length").orElse(-1L)
      if (total > limit) {
        res.body().close()
        throw IllegalStateException("升级包超过大小上限 ${limit / 1024 / 1024} MB")
      }
      res.body().use { input -> Files.newOutputStream(part).use { out -> pump(input, out, total, limit) } }
      Files.move(part, target, StandardCopyOption.REPLACE_EXISTING)
    } catch (e: Exception) {
      runCatching { Files.deleteIfExists(part) }
      progressRef.set(RemoteProgress(idle = false, phase = "error", pct = 0, label = e.message ?: "下载失败"))
      throw e
    }

    progressRef.set(RemoteProgress(idle = false, phase = "verify", pct = -1, label = "校验中"))
    return try {
      val result = upgradeService.inspectFile(target)
      progressRef.set(RemoteProgress(idle = true))
      result
    } catch (e: Exception) {
      progressRef.set(RemoteProgress(idle = false, phase = "error", pct = 0, label = e.message ?: "校验失败"))
      throw e
    }
  }

  fun cached(): List<CachedPackage> {
    if (!Files.isDirectory(downloads)) return emptyList()
    val staged = readCacheStaged()
    return Files
      .list(downloads)
      .use { s -> s.filter { it.fileName.toString().endsWith(".zip") }.toList() }
      .map { p ->
        val version = versionOfFileName(p.fileName.toString())
        CachedPackage(
          version = version,
          fileName = p.fileName.toString(),
          sizeBytes = runCatching { Files.size(p) }.getOrDefault(0L),
          downloadedAt = runCatching { Files.getLastModifiedTime(p).toInstant().toString() }.getOrNull(),
          staged = version != null && version == staged,
        )
      }.sortedByDescending { it.downloadedAt ?: "" }
  }

  fun verifyCached(): UpgradeInspect {
    val list = cached()
    val latest = list.maxByOrNull { it.downloadedAt ?: "" } ?: throw IllegalStateException("没有已下载的升级包")
    progressRef.set(RemoteProgress(idle = false, phase = "verify", pct = -1, label = "校验中"))
    return try {
      val result = upgradeService.inspectFile(downloads.resolve(latest.fileName))
      progressRef.set(RemoteProgress(idle = true))
      result
    } catch (e: Exception) {
      progressRef.set(RemoteProgress(idle = false, phase = "error", pct = 0, label = e.message ?: "校验失败"))
      throw e
    }
  }

  fun clearDownloads(): List<CachedPackage> {
    if (Files.isDirectory(downloads)) {
      val targets =
        Files.list(downloads).use { s ->
          s
            .filter { p ->
              val n = p.fileName.toString()
              n.endsWith(".zip") || n.endsWith(".part")
            }.toList()
        }
      targets.forEach { runCatching { Files.deleteIfExists(it) } }
    }
    return cached()
  }

  private fun readCacheStaged(): String? = runCatching { upgradeService.status().stagedVersion }.getOrNull()

  private fun versionOfFileName(name: String): String? {
    val m = Regex("""(?i)lantai[_-]([0-9][0-9A-Za-z.\-_]*?)[_-]upgrade\.zip""").find(name) ?: return null
    return m.groupValues[1]
  }

  private fun fresh(cache: RemoteCache): Boolean {
    val at = cache.checkedAt ?: return false
    val when0 = runCatching { Instant.parse(at) }.getOrNull() ?: return false
    return Duration.between(when0, Instant.now()).toHours() < cacheHours
  }

  private fun build(
    cache: RemoteCache,
    current: String,
    error: String?,
  ): RemoteInfo {
    val releases = cache.releases.sortedByDescending { it.publishedAt ?: "" }
    val latest =
      releases
        .filter { !it.prerelease && it.version.isNotBlank() }
        .maxWithOrNull { a, b -> compareVersions(a.version, b.version) }
    val cachedNow = cached().firstOrNull()
    return RemoteInfo(
      current = current,
      source = cache.source ?: feed,
      reachable = error == null,
      error = error,
      checkedAt = cache.checkedAt,
      latest = latest,
      releases = releases,
      cached = cachedNow,
    )
  }

  private fun fetchFeed(): List<RemoteRelease> {
    val req =
      HttpRequest
        .newBuilder(URI.create(feed))
        .timeout(Duration.ofSeconds(10))
        .header("User-Agent", "Lantai/${upgradeService.currentVersion}")
        .header("Accept", "application/vnd.github+json")
        .GET()
        .build()
    val res = client.send(req, HttpResponse.BodyHandlers.ofString())
    if (res.statusCode() !in 200..299) throw IllegalStateException("HTTP ${res.statusCode()}")
    return parseFeed(res.body())
  }

  private fun parseFeed(body: String): List<RemoteRelease> {
    val node = objectMapper.readTree(body)
    val arr =
      when {
        node.isArray -> node
        node.has("releases") && node["releases"].isArray -> node["releases"]
        else -> return emptyList()
      }
    return arr.mapNotNull { item -> toRelease(item) }
  }

  private fun toRelease(item: com.fasterxml.jackson.databind.JsonNode): RemoteRelease? {
    val version =
      (
        item.path("version").asText("").ifBlank { item.path("tag_name").asText("") }
      ).trim().removePrefix("v").removePrefix("V")
    if (version.isBlank()) return null
    val asset = pickAsset(item, version)
    val digest = asset?.path("digest")?.asText("")?.removePrefix("sha256:")?.ifBlank { null }
    return RemoteRelease(
      version = version,
      name = item.path("name").asText("").ifBlank { null },
      notes = item.path("body").asText("").ifBlank { item.path("notes").asText("").ifBlank { null } },
      publishedAt = item.path("published_at").asText("").ifBlank { item.path("publishedAt").asText("").ifBlank { null } },
      prerelease = item.path("prerelease").asBoolean(false),
      assetName = asset?.path("name")?.asText("")?.ifBlank { item.path("assetName").asText("").ifBlank { null } },
      assetUrl =
        asset?.path("browser_download_url")?.asText("")?.ifBlank {
          item.path("assetUrl").asText("").ifBlank { item.path("url").asText("").ifBlank { null } }
        },
      sizeBytes = asset?.path("size")?.asLong(0L)?.takeIf { it > 0 },
      digest = digest,
    )
  }

  private fun pickAsset(
    item: com.fasterxml.jackson.databind.JsonNode,
    version: String,
  ): com.fasterxml.jackson.databind.JsonNode? {
    val assets = item.path("assets")
    if (!assets.isArray) return null
    val zip = assets.filter { it.path("name").asText("").endsWith("-upgrade.zip", ignoreCase = true) }
    val exact = zip.firstOrNull { it.path("name").asText("").contains(version) }
    return exact ?: zip.firstOrNull()
  }

  private fun resolveAssetName(
    raw: String?,
    url: String,
    version: String,
  ): String {
    val candidate =
      raw?.trim()?.takeIf { it.isNotBlank() }
        ?: url.substringAfterLast('/').substringBefore('?').substringBefore('#')
    val base =
      runCatching { Path.of(candidate).fileName.toString() }
        .getOrNull()
        ?.trim()
        .orEmpty()
        .ifBlank { "lantai-$version-upgrade.zip" }
    if (!SAFE_ASSET_NAME.matches(base)) throw IllegalStateException("升级包名称不合法：$base")
    return base
  }

  private fun requireAllowedUrl(raw: String): URI =
    runCatching { OutboundUrlGuard.requireAllowed(raw, allowedHosts) }
      .getOrElse { throw IllegalStateException("升级包地址不可用：${it.message}") }

  private fun openDownload(uri: URI): HttpResponse<InputStream> {
    var current = uri
    repeat(MAX_REDIRECTS) {
      val req =
        HttpRequest
          .newBuilder(current)
          .timeout(Duration.ofMinutes(20))
          .header("User-Agent", "Lantai/${upgradeService.currentVersion}")
          .header("Accept", "application/octet-stream")
          .GET()
          .build()
      val res = downloadClient.send(req, HttpResponse.BodyHandlers.ofInputStream())
      if (res.statusCode() !in 300..399) return res
      val location = res.headers().firstValue("location").orElse(null)
      res.body().close()
      if (location.isNullOrBlank()) throw IllegalStateException("下载失败：HTTP ${res.statusCode()}")
      current = requireAllowedUrl(current.resolve(location).toString())
    }
    throw IllegalStateException("下载失败：重定向次数过多")
  }

  private fun pump(
    input: InputStream,
    output: OutputStream,
    total: Long,
    limit: Long,
  ) {
    val buf = ByteArray(64 * 1024)
    var read = 0L
    var mark = -1L
    while (true) {
      val n = input.read(buf)
      if (n <= 0) break
      read += n
      if (read > limit) throw IllegalStateException("升级包超过大小上限 ${limit / 1024 / 1024} MB")
      output.write(buf, 0, n)
      read += n
      if (read - mark >= 2L * 1024 * 1024) {
        mark = read
        val pct = if (total > 0) (read * 100 / total).toInt().coerceIn(0, 99) else -1
        progressRef.set(RemoteProgress(idle = false, phase = "download", pct = pct, label = sizeLabel(read, total)))
      }
    }
    progressRef.set(RemoteProgress(idle = false, phase = "download", pct = 100, label = sizeLabel(read, total)))
  }

  private fun sizeLabel(
    read: Long,
    total: Long,
  ): String {
    fun human(b: Long): String =
      when {
        b >= 1024L * 1024 * 1024 -> "%.1f GB".format(b / 1024.0 / 1024 / 1024)
        b >= 1024L * 1024 -> "%.1f MB".format(b / 1024.0 / 1024)
        b >= 1024L -> "%.0f KB".format(b / 1024.0)
        else -> "$b B"
      }
    return if (total > 0) human(read) + " / " + human(total) else human(read)
  }

  private fun readCache(): RemoteCache? {
    if (!Files.isRegularFile(cacheFile)) return null
    return runCatching { objectMapper.readValue(Files.readString(cacheFile), RemoteCache::class.java) }.getOrNull()
  }

  private fun writeCache(cache: RemoteCache) {
    runCatching {
      Files.createDirectories(root)
      Files.writeString(cacheFile, objectMapper.writeValueAsString(cache))
    }.onFailure { remoteLogger.warn(it) { "Cannot persist upgrade feed cache" } }
  }
}

data class RemoteRelease(
  val version: String,
  val name: String? = null,
  val notes: String? = null,
  val publishedAt: String? = null,
  val prerelease: Boolean = false,
  val assetName: String? = null,
  val assetUrl: String? = null,
  val sizeBytes: Long? = null,
  val digest: String? = null,
)

data class RemoteCache(
  val checkedAt: String? = null,
  val source: String? = null,
  val releases: List<RemoteRelease> = emptyList(),
)

data class CachedPackage(
  val version: String? = null,
  val fileName: String,
  val sizeBytes: Long,
  val downloadedAt: String? = null,
  val staged: Boolean = false,
)

data class RemoteInfo(
  val current: String,
  val source: String,
  val reachable: Boolean,
  val error: String? = null,
  val checkedAt: String? = null,
  val latest: RemoteRelease? = null,
  val releases: List<RemoteRelease> = emptyList(),
  val cached: CachedPackage? = null,
)

data class RemoteProgress(
  val idle: Boolean = true,
  val phase: String = "",
  val pct: Int = 0,
  val label: String = "",
)

data class FetchRequest(
  val version: String? = null,
  val assetUrl: String? = null,
  val assetName: String? = null,
)
