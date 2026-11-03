package org.lantai.infrastructure.online

import com.fasterxml.jackson.databind.JsonNode
import io.github.oshai.kotlinlogging.KotlinLogging
import org.lantai.infrastructure.log.ExtensionLog
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}
private const val RELEASES_API = "https://api.github.com/repos/Suwayomi/Suwayomi-Server/releases/latest"

data class SuwayomiRelease(
  val tag: String,
  val publishedAt: String?,
  val assetName: String?,
  val assetUrl: String?,
  val size: Long,
)

@Service
class SuwayomiInstaller(
  private val properties: OnlineProperties,
  private val configWriter: SuwayomiConfigWriter,
  private val extLog: ExtensionLog,
) {
  private val client: RestClient by lazy {
    RestClient
      .builder()
      .requestFactory(
        SimpleClientHttpRequestFactory().apply {
          setConnectTimeout(Duration.ofSeconds(15))
          setReadTimeout(Duration.ofMinutes(30))
        },
      ).build()
  }

  private val indexClient: RestClient by lazy {
    RestClient
      .builder()
      .requestFactory(
        SimpleClientHttpRequestFactory().apply {
          setConnectTimeout(Duration.ofSeconds(5))
          setReadTimeout(Duration.ofSeconds(15))
        },
      ).build()
  }

  private val catalogCache = AtomicReference<Pair<Instant, List<SuwayomiCatalogEntry>>?>(null)

  fun latestRelease(): SuwayomiRelease {
    val node =
      try {
        client
          .get()
          .uri(RELEASES_API)
          .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
          .header(HttpHeaders.USER_AGENT, "lantai")
          .retrieve()
          .body(JsonNode::class.java)
      } catch (e: RestClientResponseException) {
        throw SuwayomiException("GitHub release lookup responded with ${e.statusCode}", e)
      } catch (e: Exception) {
        throw SuwayomiException("GitHub release lookup failed: ${e.message}", e)
      } ?: throw SuwayomiException("Empty GitHub release response")

    val tag = node.path("tag_name").asText("")
    val asset = node.path("assets").firstOrNull { it.path("name").asText("").endsWith(".jar") }
    return SuwayomiRelease(
      tag = tag,
      publishedAt = node.path("published_at").asText(null),
      assetName = asset?.path("name")?.asText(null),
      assetUrl = asset?.path("url")?.asText(null),
      size = asset?.path("size")?.asLong(0) ?: 0,
    )
  }

  fun downloadLatest(onProgress: (Int, String) -> Unit = { _, _ -> }): Path {
    val release = latestRelease()
    val url = release.assetUrl ?: throw SuwayomiException("Latest release ${release.tag} has no jar asset")
    val staging = stagingDir().resolve(release.assetName ?: "Suwayomi-Server.jar")
    Files.createDirectories(staging.parent)
    try {
      downloadTo(url, staging, onProgress)
    } catch (e: SuwayomiException) {
      throw e
    } catch (e: Exception) {
      throw SuwayomiException("Downloading Suwayomi ${release.tag} failed: ${e.message}", e)
    }
    validatePackage(staging)
    return installFromFile(staging)
  }

  fun installFromFile(file: Path): Path {
    validatePackage(file)
    val target = configWriter.jarPath()
    Files.createDirectories(target.parent)
    if (file.toAbsolutePath().normalize() != target.toAbsolutePath().normalize()) {
      Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING)
    }
    logger.info { "Installed Suwayomi jar at $target (${Files.size(target)} bytes)" }
    return target
  }

  fun importJar(bytes: ByteArray): Path = writeJar(bytes, "Suwayomi-Server.jar")

  fun stagingDir(): Path = configWriter.home().resolve("bin")

  fun installedVersion(): String? {
    if (!configWriter.jarPath().toFile().exists()) return null
    val manifest =
      runCatching {
        java.util.jar.JarFile(configWriter.jarPath().toFile()).use { jar ->
          jar.manifest?.mainAttributes?.getValue("Implementation-Version")
            ?: jar.manifest?.mainAttributes?.getValue("Specification-Version")
        }
      }.getOrNull()
    return manifest
  }

  fun isSupported(version: String?): Boolean {
    val normalized = normalize(version) ?: return true
    val min = normalize(properties.suwayomiMinVersion) ?: return true
    val max = normalize(properties.suwayomiMaxVersion) ?: return true
    return compare(normalized, min) >= 0 && compare(normalized, max) < 0
  }

  fun catalog(force: Boolean = false): List<SuwayomiCatalogEntry> {
    val cached = catalogCache.get()
    if (!force && cached != null && Duration.between(cached.first, Instant.now()).toMinutes() < properties.extensionIndexCacheMinutes) {
      return cached.second
    }
    val sources = listOfNotNull(properties.extensionIndexUrl, properties.extensionIndexMirrorUrl).filter { it.isNotBlank() }
    var lastError: Exception? = null
    for (url in sources) {
      val started = System.currentTimeMillis()
      val bytes =
        runCatching {
          indexClient
            .get()
            .uri(url)
            .header(HttpHeaders.ACCEPT, "application/octet-stream")
            .header(HttpHeaders.USER_AGENT, "lantai")
            .retrieve()
            .body(ByteArray::class.java)
        }.onFailure {
          lastError = it as? Exception
          extLog.warn("扩展清单拉取失败 $url（${System.currentTimeMillis() - started}ms）：${it.javaClass.simpleName}: ${it.message}")
        }.getOrNull() ?: continue
      val parsed = runCatching { ExtensionIndexParser.parse(bytes) }
      val entries = parsed.getOrNull()
      if (entries == null) {
        extLog.warn("扩展清单解析失败 $url：${parsed.exceptionOrNull()?.message}")
        continue
      }
      if (entries.isNotEmpty()) {
        catalogCache.set(Instant.now() to entries)
        logger.info { "Loaded ${entries.size} catalog entries from $url" }
        extLog.info("扩展清单加载成功 ${entries.size} 条，源 $url，${bytes.size} 字节，耗时 ${System.currentTimeMillis() - started}ms")
        return entries
      }
      extLog.warn("扩展清单为空 $url（${bytes.size} 字节）")
    }
    extLog.error("扩展清单不可用：所有来源均失败", lastError)
    throw SuwayomiException("Unable to load extension index: ${lastError?.message ?: "no source reachable"}", lastError)
  }

  fun downloadExtensionJar(entry: SuwayomiCatalogEntry): ByteArray {
    val url = entry.jarUrl ?: entry.apkUrl ?: throw SuwayomiException("Extension ${entry.pkgName} has no downloadable jar")
    val bytes =
      try {
        client
          .get()
          .uri(url)
          .header(HttpHeaders.ACCEPT, "application/octet-stream")
          .header(HttpHeaders.USER_AGENT, "lantai")
          .retrieve()
          .body(ByteArray::class.java)
      } catch (e: Exception) {
        throw SuwayomiException("Downloading extension ${entry.pkgName} failed: ${e.message}", e)
      } ?: throw SuwayomiException("Empty download for extension ${entry.pkgName}")
    if (bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) {
      throw SuwayomiException("Downloaded extension ${entry.pkgName} is not a valid archive")
    }
    return bytes
  }

  private fun downloadTo(
    url: String,
    target: Path,
    onProgress: (Int, String) -> Unit,
  ) {
    client
      .get()
      .uri(url)
      .header(HttpHeaders.ACCEPT, "application/octet-stream")
      .header(HttpHeaders.USER_AGENT, "lantai")
      .exchange { _, response ->
        if (!response.statusCode.is2xxSuccessful) throw SuwayomiException("HTTP ${response.statusCode.value()} from $url")
        val total = response.headers.contentLength
        var written = 0L
        var lastReport = -1
        response.body.use { input ->
          Files.newOutputStream(target).use { out ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
              val read = input.read(buffer)
              if (read < 0) break
              out.write(buffer, 0, read)
              written += read
              if (total > 0) {
                val pct = (written * 100 / total).toInt().coerceIn(0, 99)
                if (pct != lastReport) {
                  lastReport = pct
                  onProgress(pct, "downloaded ${written / 1048576} MB of ${total / 1048576} MB")
                }
              }
            }
          }
        }
        if (total > 0 && written != total) throw SuwayomiException("Incomplete download from $url: $written of $total bytes")
      }
  }

  private fun writeJar(
    bytes: ByteArray,
    fileName: String,
  ): Path {
    val target = configWriter.jarPath()
    Files.createDirectories(target.parent)
    val temp = target.resolveSibling("$fileName.download")
    Files.write(temp, bytes)
    validatePackage(temp)
    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
    logger.info { "Installed Suwayomi jar at $target (${bytes.size} bytes)" }
    return target
  }

  fun validatePackage(file: Path) {
    val head = ByteArray(2)
    val read = Files.newInputStream(file).use { it.read(head) }
    if (read < 2 || head[0] != 'P'.code.toByte() || head[1] != 'K'.code.toByte()) {
      throw SuwayomiException("Payload is not a valid jar archive")
    }
  }

  private fun normalize(version: String?): List<Int>? {
    if (version.isNullOrBlank()) return null
    val digits = version.trim().removePrefix("v").split('.', '-', '+')
    val numbers = digits.take(3).mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }
    return numbers.takeIf { it.isNotEmpty() }
  }

  private fun compare(
    left: List<Int>,
    right: List<Int>,
  ): Int {
    for (index in 0 until maxOf(left.size, right.size)) {
      val l = left.getOrElse(index) { 0 }
      val r = right.getOrElse(index) { 0 }
      if (l != r) return l.compareTo(r)
    }
    return 0
  }
}
