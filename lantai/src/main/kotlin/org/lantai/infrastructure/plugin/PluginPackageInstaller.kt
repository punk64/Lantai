package org.lantai.infrastructure.plugin

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.f4b6a3.tsid.TsidCreator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.zip.ZipInputStream
import kotlin.io.path.exists

private val logger = KotlinLogging.logger {}

@Service
class PluginPackageInstaller(
  private val paths: PluginPaths,
) {
  private val mapper = ObjectMapper()

  fun install(
    zipPath: String,
    onProgress: ((Int, String) -> Unit)? = null,
  ): InstalledPackage {
    val source = resolvePackage(zipPath)
    val total = runCatching { Files.size(source) }.getOrDefault(0L).coerceAtLeast(1L)
    if (total > PluginContract.MAX_PACKAGE_BYTES) {
      throw PluginRequestException("插件包超过 ${formatSize(PluginContract.MAX_PACKAGE_BYTES)} 上限")
    }
    val staging = paths.stagingDir(TsidCreator.getTsid256().toString().lowercase())
    try {
      Files.createDirectories(staging)
      extract(source, staging, total, onProgress)
      val manifest = readManifest(staging)
      val target = paths.packageDir(manifest.pluginId, manifest.version)
      if (target.exists()) throw PluginConflictException("插件 ${manifest.pluginId} ${manifest.version} 已安装")
      Files.createDirectories(target.parent)
      Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
      logger.info { "Installed plugin ${manifest.pluginId} ${manifest.version} into $target" }
      return manifest.copy(homeDir = target.toString())
    } finally {
      deleteRecursively(staging)
      runCatching { Files.deleteIfExists(paths.stagingRoot()) }
    }
  }

  private fun resolvePackage(zipPath: String): Path {
    val raw = zipPath.trim()
    if (raw.isEmpty()) throw PluginRequestException("插件包路径不能为空")
    val path = runCatching { Paths.get(raw).toAbsolutePath().normalize() }.getOrElse { throw PluginRequestException("插件包路径不合法") }
    if (!Files.isRegularFile(path)) throw PluginRequestException("插件包不存在：$path")
    if (!path.fileName.toString().lowercase().endsWith(".zip")) throw PluginRequestException("插件包必须是 zip 文件")
    return path
  }

  private fun extract(
    zip: Path,
    target: Path,
    total: Long,
    onProgress: ((Int, String) -> Unit)?,
  ) {
    val names = mutableListOf<String>()
    var totalBytes = 0L
    Files.newInputStream(zip).use { raw ->
      val counting = CountingStream(raw)
      ZipInputStream(counting).use { input ->
        var count = 0
        var mark = 0L
        while (true) {
          val entry = input.nextEntry ?: break
          count += 1
          if (count > PluginContract.MAX_ENTRIES) throw PluginRequestException("插件包条目过多（上限 ${PluginContract.MAX_ENTRIES}）")
          names += entry.name
          val resolved = target.resolve(entry.name).normalize()
          if (!resolved.startsWith(target)) throw PluginRequestException("插件包包含越界路径：${entry.name}")
          if (entry.isDirectory) {
            Files.createDirectories(resolved)
          } else {
            Files.createDirectories(resolved.parent)
            val written = Files.copy(input, resolved, REPLACE_EXISTING)
            if (written > PluginContract.MAX_ENTRY_BYTES) throw PluginRequestException("单个文件超过 ${formatSize(PluginContract.MAX_ENTRY_BYTES)}：${entry.name}")
            totalBytes += written
            if (totalBytes > PluginContract.MAX_TOTAL_BYTES) throw PluginRequestException("插件解压后超过 ${formatSize(PluginContract.MAX_TOTAL_BYTES)}")
          }
          input.closeEntry()
          if (onProgress != null) {
            val read = counting.count
            if (read - mark >= PROGRESS_STEP) {
              mark = read
              val pct = (read * 100 / total).toInt().coerceIn(0, 99)
              onProgress(pct, "正在解压 " + formatSize(read.coerceAtMost(total)) + " / " + formatSize(total))
            }
          }
        }
      }
    }
    onProgress?.invoke(100, "解压完成")
    val layout = PluginContract.validatePackageLayout(names)
    if (layout.isNotEmpty()) throw PluginRequestException(PluginContract.describe(layout))
  }

  private fun readManifest(staging: Path): InstalledPackage {
    val file = staging.resolve(MANIFEST_FILE)
    if (!file.exists()) throw PluginRequestException("插件包缺少 $MANIFEST_FILE")
    if (Files.size(file) > PluginContract.MAX_MANIFEST_BYTES) {
      throw PluginRequestException("$MANIFEST_FILE 超过 ${formatSize(PluginContract.MAX_MANIFEST_BYTES)}")
    }
    val node =
      runCatching { mapper.readTree(file.toFile()) }.getOrElse {
        throw PluginRequestException("$MANIFEST_FILE 不是合法 JSON")
      }
    val issues =
      PluginContract.validate(node) { rel ->
        val target = staging.resolve(rel).normalize()
        target.startsWith(staging) && Files.isRegularFile(target)
      }
    if (issues.isNotEmpty()) throw PluginRequestException(PluginContract.describe(issues))
    val pluginId = node.path("id").asText("").trim()
    if (!ID_PATTERN.matches(pluginId)) throw PluginRequestException("$MANIFEST_FILE 的 id 不合法：$pluginId")
    val version = node.path("version").asText("").trim()
    if (version.isEmpty()) throw PluginRequestException("$MANIFEST_FILE 缺少 version")
    if (!VERSION_PATTERN.matches(version)) throw PluginRequestException("$MANIFEST_FILE 的 version 不合法：$version")
    val protocol = node.path("protocol").asInt(node.path("apiVersion").asInt(0))
    if (protocol !in SUPPORTED_PROTOCOLS) throw PluginRequestException("插件协议版本 $protocol 不受支持")
    return InstalledPackage(
      pluginId = pluginId,
      name = node.path("name").asText("").trim().ifEmpty { pluginId },
      version = version,
      protocol = protocol,
      minHost = node.path("minHost").asText("").trim(),
      runtimeKind = node.path("runtime").asText("").trim().ifEmpty { PluginProcessManager.RUNTIME_JVM },
      entry = node.path("entry").asText("").trim(),
      memoryMb = node.path("memoryMb").asInt(PluginProcessManager.DEFAULT_MEMORY_MB),
      capabilities = readCapabilityNames(node.path("capabilities")),
      homeDir = "",
    )
  }

  private fun deleteRecursively(path: Path) {
    if (!path.exists()) return
    runCatching {
      Files.walk(path).use { walk ->
        walk.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
      }
    }
  }

  private companion object {
    private const val MANIFEST_FILE = "plugin.json"
    private const val PROGRESS_STEP = 1024L * 1024
    private val SUPPORTED_PROTOCOLS = setOf(1, 2)
    private val ID_PATTERN = Regex("^[a-z0-9]+([._-][a-z0-9]+)+$")
    private val VERSION_PATTERN = Regex("^[0-9A-Za-z][0-9A-Za-z._-]*$")
  }
}

private fun formatSize(bytes: Long): String =
  when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
  }

private class CountingStream(
  private val delegate: java.io.InputStream,
) : java.io.InputStream() {
  var count = 0L
    private set

  override fun read(): Int {
    val value = delegate.read()
    if (value >= 0) count++
    return value
  }

  override fun read(
    b: ByteArray,
    off: Int,
    len: Int,
  ): Int {
    val value = delegate.read(b, off, len)
    if (value > 0) count += value
    return value
  }

  override fun available(): Int = delegate.available()

  override fun close() = delegate.close()
}

data class InstalledPackage(
  val pluginId: String,
  val name: String,
  val version: String,
  val protocol: Int,
  val minHost: String,
  val runtimeKind: String,
  val entry: String,
  val memoryMb: Int,
  val capabilities: List<String>,
  val homeDir: String,
)
