package org.lantai.infrastructure.calibre

import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.springframework.http.HttpHeaders
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

private val logger = KotlinLogging.logger {}

private const val XZ_MAGIC_1 = 0xFD.toByte()
private const val XZ_MAGIC_2 = 0x37.toByte()

data class CalibreRelease(
  val version: String?,
  val fileName: String,
  val url: String,
  val urls: List<String> = listOf(url),
  val size: Long? = null,
)

@Service
class CalibreInstaller(
  private val properties: CalibreProperties,
  private val runtime: CalibreRuntime,
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

  fun latestRelease(): CalibreRelease = if (isWindows()) windowsRelease() else linuxRelease()

  fun downloadInstaller(onProgress: (Int, String) -> Unit = { _, _ -> }): Path {
    val release = latestRelease()
    val candidates =
      linkedSetOf<String>().apply {
        addAll(release.urls)
        properties.mirror.takeIf { it.isNotBlank() }?.let { add(prefixMirror(it, release.url)) }
      }
    val dir = runtime.stagedInstallerDir()
    Files.createDirectories(dir)
    val target = dir.resolve(release.fileName)
    var lastError: Exception? = null
    for (url in candidates) {
      try {
        logger.info { "Downloading Calibre ${release.version} from $url" }
        downloadTo(url, target, onProgress)
        validatePackage(target)
        return target
      } catch (e: Exception) {
        lastError = e
        logger.warn { "Calibre download from $url failed: ${e.message}" }
      }
    }
    throw CalibreException("Downloading Calibre ${release.version} failed: ${lastError?.message}", lastError)
  }

  fun importInstaller(
    bytes: ByteArray,
    fileName: String? = null,
  ): Path {
    val name = fileName?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: defaultFileName()
    val dir = runtime.stagedInstallerDir()
    Files.createDirectories(dir)
    val target = dir.resolve(name)
    val temp = dir.resolve("$name.download")
    Files.write(temp, bytes)
    validatePayload(temp, name)
    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
    logger.info { "Staged Calibre package at $target (${bytes.size} bytes)" }
    return target
  }

  fun install(
    installer: Path,
    onProgress: (Int, String) -> Unit = { _, _ -> },
  ): CalibreStatus {
    val target = runtime.installDir()
    Files.createDirectories(target)
    return if (isWindows()) installWindows(installer, target, onProgress) else installUnix(installer, target, onProgress)
  }

  private fun installWindows(
    installer: Path,
    target: Path,
    onProgress: (Int, String) -> Unit,
  ): CalibreStatus {
    val logFile = runtime.stagedInstallerDir().resolve("install.log").toFile()
    logFile.parentFile?.mkdirs()
    logger.info { "Running Calibre installer $installer into $target" }
    onProgress(5, "running calibre portable installer")
    val process =
      ProcessBuilder(installer.toString(), target.toString())
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
        .start()
    if (!process.waitFor(properties.installTimeoutSeconds.toLong(), TimeUnit.SECONDS)) {
      process.destroyForcibly()
      throw CalibreException("Calibre installer did not finish within ${properties.installTimeoutSeconds}s")
    }
    if (process.exitValue() != 0) {
      throw CalibreException("Calibre installer exited with ${process.exitValue()}: ${tail(logFile)}")
    }
    return finishInstall(logFile)
  }

  private fun installUnix(
    archive: Path,
    target: Path,
    onProgress: (Int, String) -> Unit,
  ): CalibreStatus {
    val name = archive.fileName.toString().lowercase()
    when {
      name.endsWith(".txz") || name.endsWith(".tar.xz") -> extractTarXz(archive, target, onProgress)
      name.endsWith(".zip") -> extractZip(archive, target, onProgress)
      else -> throw CalibreException("Unsupported Calibre package format: $name")
    }
    return finishInstall(null)
  }

  private fun finishInstall(logFile: java.io.File?): CalibreStatus {
    val status = runtime.status()
    if (!status.available) {
      val detail = logFile?.let { ": ${tail(it)}" }.orEmpty()
      throw CalibreException("Calibre package was unpacked but ebook-convert is still unavailable$detail")
    }
    return status
  }

  private fun extractTarXz(
    archive: Path,
    target: Path,
    onProgress: (Int, String) -> Unit,
  ) {
    val root = target.toAbsolutePath().normalize()
    val total = Files.size(archive).coerceAtLeast(1L)
    onProgress(10, "unpacking ${archive.fileName}")
    Files.newInputStream(archive).use { raw ->
      BufferedInputStream(raw).use { buffered ->
        XZCompressorInputStream(buffered).use { xz ->
          TarArchiveInputStream(xz).use { tar ->
            var count = 0
            while (true) {
              val entry = tar.nextEntry ?: break
              val resolved = root.resolve(entry.name).normalize()
              if (!resolved.startsWith(root)) throw CalibreException("Archive entry escapes target: ${entry.name}")
              when {
                entry.isDirectory -> Files.createDirectories(resolved)
                entry.isSymbolicLink -> linkInside(root, resolved, entry.linkName)
                else -> {
                  Files.createDirectories(resolved.parent)
                  Files.copy(tar, resolved, StandardCopyOption.REPLACE_EXISTING)
                  if (entry.mode and 0b001_001_001 != 0) runCatching { resolved.toFile().setExecutable(true, false) }
                }
              }
              count++
              if (count % 200 == 0) {
                val pct = (10 + (tar.bytesRead * 80 / total).toInt()).coerceIn(10, 90)
                onProgress(pct, "unpacked $count entries")
              }
            }
            onProgress(92, "unpacked $count entries")
          }
        }
      }
    }
  }

  private fun extractZip(
    archive: Path,
    target: Path,
    onProgress: (Int, String) -> Unit,
  ) {
    val root = target.toAbsolutePath().normalize()
    onProgress(10, "unpacking ${archive.fileName}")
    Files.newInputStream(archive).use { raw ->
      ZipInputStream(BufferedInputStream(raw)).use { zip ->
        var count = 0
        while (true) {
          val entry = zip.nextEntry ?: break
          val resolved = root.resolve(entry.name).normalize()
          if (!resolved.startsWith(root)) throw CalibreException("Archive entry escapes target: ${entry.name}")
          if (entry.isDirectory) {
            Files.createDirectories(resolved)
          } else {
            Files.createDirectories(resolved.parent)
            Files.copy(zip, resolved, StandardCopyOption.REPLACE_EXISTING)
            count++
          }
        }
        onProgress(92, "unpacked $count entries")
      }
    }
  }

  private fun linkInside(
    root: Path,
    link: Path,
    linkName: String,
  ) {
    val target = root.resolve(linkName).normalize()
    if (!target.startsWith(root)) throw CalibreException("Archive link escapes target: $linkName")
    Files.createDirectories(link.parent)
    Files.deleteIfExists(link)
    runCatching { Files.createSymbolicLink(link, root.relativize(target)) }
      .onFailure { logger.debug(it) { "Unable to create symlink $link -> $target" } }
  }

  private fun downloadTo(
    url: String,
    target: Path,
    onProgress: (Int, String) -> Unit,
  ): Long {
    Files.createDirectories(target.parent)
    val temp = target.resolveSibling("${target.fileName}.download")
    val written =
      client
        .get()
        .uri(url)
        .header(HttpHeaders.ACCEPT, "application/octet-stream")
        .header(HttpHeaders.USER_AGENT, "lantai")
        .exchange { _, response ->
          if (!response.statusCode.is2xxSuccessful) throw CalibreException("HTTP ${response.statusCode.value()} from $url")
          val total = response.headers.contentLength
          var count = 0L
          var lastReport = -1
          response.body.use { input ->
            Files.newOutputStream(temp).use { out ->
              val buffer = ByteArray(1 shl 16)
              while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                count += read
                if (total > 0) {
                  val pct = (count * 100 / total).toInt().coerceIn(0, 99)
                  if (pct != lastReport) {
                    lastReport = pct
                    onProgress(pct, "downloaded ${count / 1048576} MB of ${total / 1048576} MB")
                  }
                }
              }
            }
          }
          if (total > 0 && count != total) throw CalibreException("Incomplete download from $url: $count of $total bytes")
          count
        } ?: throw CalibreException("Empty response from $url")
    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
    logger.info { "Downloaded Calibre package to $target ($written bytes)" }
    return written
  }

  fun validatePackage(file: Path) {
    validatePayload(file, file.fileName.toString())
  }

  private fun validatePayload(
    file: Path,
    fileName: String,
  ) {
    val lower = fileName.lowercase()
    val head = ByteArray(6)
    val read = Files.newInputStream(file).use { it.read(head) }
    val ok =
      when {
        lower.endsWith(".exe") -> read >= 2 && head[0] == 'M'.code.toByte() && head[1] == 'Z'.code.toByte()
        lower.endsWith(".txz") || lower.endsWith(".tar.xz") -> read >= 2 && head[0] == XZ_MAGIC_1 && head[1] == XZ_MAGIC_2
        lower.endsWith(".zip") -> read >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
        else -> throw CalibreException("Unsupported Calibre package format: $fileName")
      }
    if (!ok) throw CalibreException("$fileName is not a valid Calibre package")
  }

  private fun defaultFileName(): String =
    if (isWindows()) "calibre-portable-installer.exe" else "calibre-${runtime.arch()}.txz"

  private fun windowsRelease(): CalibreRelease {
    val location = resolveRedirect(properties.portableUrl)
    val fileName = location.substringAfterLast('/').substringBefore('?')
    if (!fileName.endsWith(".exe", true)) {
      throw CalibreException("Calibre portable build is not a Windows installer: $fileName")
    }
    val version = Regex("""\d+\.\d+(?:\.\d+)?""").find(fileName)?.value
    return CalibreRelease(version = version, fileName = fileName, url = location)
  }

  private fun linuxRelease(): CalibreRelease {
    val version =
      githubTag()?.removePrefix("v")?.takeIf { it.isNotBlank() }
        ?: throw CalibreException(
          "Unable to resolve the latest Calibre version; download the package manually and upload it",
        )
    val arch = runtime.arch()
    val fileName = "calibre-$version-$arch.txz"
    val official = "${properties.linuxBaseUrl.trimEnd('/')}/$version/$fileName"
    val fallback = "https://github.com/kovidgoyal/calibre/releases/download/v$version/$fileName"
    return CalibreRelease(
      version = version,
      fileName = fileName,
      url = official,
      urls = listOf(official, fallback),
    )
  }

  private fun githubTag(): String? =
    runCatching {
      val node =
        client
          .get()
          .uri(properties.releasesApi)
          .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
          .header(HttpHeaders.USER_AGENT, "lantai")
          .retrieve()
          .body(Map::class.java)
      node?.get("tag_name")?.toString()
    }.onFailure { logger.warn(it) { "Calibre release lookup failed" } }
      .getOrNull()

  private fun prefixMirror(
    mirror: String,
    url: String,
  ): String = mirror.trimEnd('/') + "/" + url

  private fun resolveRedirect(source: String): String {
    val connection = URI(source).toURL().openConnection() as HttpURLConnection
    connection.instanceFollowRedirects = false
    connection.requestMethod = "GET"
    connection.connectTimeout = 15_000
    connection.readTimeout = 15_000
    connection.setRequestProperty(HttpHeaders.USER_AGENT, "lantai")
    return try {
      val code = connection.responseCode
      val location = connection.getHeaderField(HttpHeaders.LOCATION)
      if (location.isNullOrBlank()) {
        throw CalibreException("Calibre download endpoint answered with $code and no redirect target")
      }
      location
    } catch (e: CalibreException) {
      throw e
    } catch (e: Exception) {
      throw CalibreException("Unable to resolve Calibre download target: ${e.message}", e)
    } finally {
      connection.disconnect()
    }
  }

  private fun tail(file: java.io.File): String =
    runCatching { file.readLines().takeLast(10).joinToString(" | ") }.getOrDefault("")

  private fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("win")
}
