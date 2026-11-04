package org.lantai.infrastructure.upgrade

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.lantai.infrastructure.runtime.install.RuntimeEnvironment
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.ZipInputStream
import kotlin.system.exitProcess

private val logger = KotlinLogging.logger {}
private const val PROGRESS_STEP = 2L * 1024 * 1024

private class CountingInputStream(
  private val delegate: InputStream,
) : InputStream() {
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

@Service
class UpgradeService(
  private val runtimeEnvironment: RuntimeEnvironment,
  private val objectMapper: ObjectMapper,
  private val buildProperties: ObjectProvider<BuildProperties>,
) {
  private val root: Path = runtimeEnvironment.configDir().resolve("upgrade")
  private val stagedDir: Path = root.resolve("staged")
  private val backupDir: Path = root.resolve("backup")

  private val inspectGuard = ReentrantLock()
  private val inspectProgress = AtomicReference(UpgradeProgress(idle = true))

  val currentVersion: String
    get() = buildProperties.getIfAvailable()?.version ?: "unknown"

  fun progress(): UpgradeProgress = inspectProgress.get()

  fun currentJar(): Path? {
    runCatching {
      val codeSource = UpgradeService::class.java.protectionDomain?.codeSource ?: return@runCatching
      val path = Paths.get(codeSource.location.toURI()).toAbsolutePath().normalize()
      if (Files.isRegularFile(path) && path.fileName.toString().endsWith(".jar")) return path
    }
    val jars =
      System.getProperty("java.class.path")
        .orEmpty()
        .split(File.pathSeparator)
        .filter { it.isNotBlank() }
        .map { Paths.get(it).toAbsolutePath().normalize() }
        .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }
    return jars.firstOrNull { it.fileName.toString().startsWith("lantai-") }
      ?: jars.firstOrNull { it.fileName.toString() == "application.jar" }
      ?: jars.firstOrNull()
  }

  fun isContainerized(): Boolean = Files.exists(Paths.get("/.dockerenv"))

  fun latestBackup(): Path? {
    if (!Files.isDirectory(backupDir)) return null
    val list =
      Files.list(backupDir).use { s ->
        s.filter { p -> p.fileName.toString().endsWith(".jar") }.toList()
      }
    return list.maxByOrNull { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L) }
  }

  fun status(): UpgradeStatus {
    val jar = currentJar()
    val staged = readManifestOrNull()?.takeIf { stagedPending(it) }
    val backupVersion = backupVersion(latestBackup(), currentVersion)
    return UpgradeStatus(
      version = currentVersion,
      jarPath = jar?.toString(),
      jarWritable = jar?.parent?.let { Files.isWritable(it) } ?: false,
      containerized = isContainerized(),
      restartMode = if (isContainerized()) "container" else "manual",
      backupVersion = backupVersion,
      stagedVersion = staged?.version,
    )
  }

  fun discard() {
    deleteRecursively(stagedDir)
  }

  private fun stagedPending(manifest: UpgradeManifest): Boolean {
    val version = manifest.version?.trim()
    if (version.isNullOrBlank()) return false
    return compareVersions(version, currentVersion) > 0
  }

  fun inspect(file: MultipartFile): UpgradeInspect {
    if (file.isEmpty) throw IllegalArgumentException("上传文件为空")
    val name = (file.originalFilename ?: "").lowercase()
    if (!name.endsWith(".zip") && !name.endsWith(".lup")) throw IllegalArgumentException("升级包必须是 .zip")
    if (file.size > 512L * 1024 * 1024) throw IllegalArgumentException("升级包超过 512MB")
    if (!inspectGuard.tryLock()) throw IllegalStateException("上一个升级包仍在校验，请稍候")
    try {
      return stage({ file.inputStream }, file.size)
    } finally {
      inspectGuard.unlock()
    }
  }

  fun inspectFile(path: Path): UpgradeInspect {
    if (!Files.isRegularFile(path)) throw IllegalArgumentException("找不到已下载的升级包")
    val name = path.fileName.toString().lowercase()
    if (!name.endsWith(".zip") && !name.endsWith(".lup")) throw IllegalArgumentException("升级包必须是 .zip")
    val size = Files.size(path)
    if (size > 512L * 1024 * 1024) throw IllegalArgumentException("升级包超过 512MB")
    if (!inspectGuard.tryLock()) throw IllegalStateException("上一个升级包仍在校验，请稍候")
    try {
      return stage({ Files.newInputStream(path) }, size)
    } finally {
      inspectGuard.unlock()
    }
  }

  private fun stage(
    open: () -> InputStream,
    size: Long,
  ): UpgradeInspect {
    try {
      discard()
      Files.createDirectories(stagedDir)

      val compressed = size.coerceAtLeast(1L)
      report("unzip", 0, "正在解压 " + formatSize(compressed))
      open().use { input ->
        val counting = CountingInputStream(input)
        unzip(counting, stagedDir, compressed)
        drain(counting)
      }
      report("unzip", 100, "解压完成，正在校验包内容")

      report("verify", -1, "正在读取包信息")
      val manifest = readManifest()
      if (!manifest.product.equals("lantai", ignoreCase = true)) throw IllegalArgumentException("不是兰台的升级包")
      val version = manifest.version?.trim()
      if (version.isNullOrBlank()) throw IllegalArgumentException("manifest.json 缺少 version")
      val compared = compareVersions(version, currentVersion)
      if (compared == 0) throw IllegalArgumentException("升级包版本与当前版本相同（$version）")
      if (compared < 0)
        throw IllegalArgumentException("升级包版本 $version 低于当前版本 $currentVersion，已拒绝降级；需要回退请用「回退」按钮")

      val jar = stagedJar()
      val jarSize = Files.size(jar).coerceAtLeast(1L)
      report("verify", -1, "正在校验 SHA-256")
      val digest = sha256(jar)
      if (manifest.sha256 != null && manifest.sha256.isNotBlank()) {
        if (!digest.equals(manifest.sha256.trim(), ignoreCase = true)) throw IllegalArgumentException("jar 校验失败，包可能损坏")
      }
      report("verify", -1, "正在检查 jar 结构")
      if (!jarStructureOk(jar)) throw IllegalArgumentException("jar 结构不合法，缺少 BOOT-INF")

      val result =
        UpgradeInspect(
          version = version,
          currentVersion = currentVersion,
          fileName = jar.fileName.toString(),
          sizeBytes = jarSize,
          sha256 = digest,
          builtAt = manifest.builtAt,
          notes = manifest.notes,
          containerized = isContainerized(),
          restartMode = if (isContainerized()) "container" else "manual",
        )
      report("done", 100, "校验通过")
      return result
    } catch (e: Exception) {
      runCatching { discard() }
      report("error", 0, e.message ?: "校验失败")
      throw e
    }
  }

  private fun report(
    phase: String,
    pct: Int,
    label: String,
  ) {
    val value = if (pct < 0) -1 else pct.coerceIn(0, 100)
    inspectProgress.set(UpgradeProgress(idle = false, phase = phase, pct = value, label = label))
  }

  private fun formatSize(bytes: Long): String =
    when {
      bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / 1024.0 / 1024 / 1024)
      bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
      bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
      else -> "$bytes B"
    }

  private fun drain(input: InputStream) {
    val buf = ByteArray(64 * 1024)
    var n = input.read(buf)
    while (n > 0) {
      n = input.read(buf)
    }
  }

  fun apply(): UpgradeApply {
    if (!inspectGuard.tryLock()) throw IllegalStateException("另一个升级任务正在进行，请稍候")
    try {
      return applyLocked()
    } finally {
      inspectGuard.unlock()
    }
  }

  private fun applyLocked(): UpgradeApply {
    val manifest = readManifest()
    val version = manifest.version?.trim()?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("缺少升级包信息")
    if (compareVersions(version, currentVersion) <= 0)
      throw IllegalStateException("暂存的升级包版本 $version 不高于当前版本 $currentVersion，已拒绝；需要回退请用「回退」按钮")
    val jar = stagedJar()
    if (!jarStructureOk(jar)) throw IllegalStateException("jar 结构不合法")
    val target = currentJar() ?: throw IllegalStateException("无法定位当前运行的 jar，请手动替换")
    val parent = target.parent ?: throw IllegalStateException("无法定位 jar 所在目录")
    if (!Files.isWritable(parent)) throw IllegalStateException("jar 所在目录不可写：$parent")

    report("backup", 0, "正在备份当前版本")
    backupCurrent(target)

    val temp = parent.resolve(target.fileName.toString() + ".new")
    try {
      report("copy", 45, "正在替换程序文件")
      copyWithProgress(jar, temp, "copy", 45, 95, "正在替换程序文件")
      Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (e: Exception) {
      runCatching { Files.deleteIfExists(temp) }
      report("error", 0, e.message ?: "替换失败")
      throw IllegalStateException("替换失败（文件可能被系统锁定）：${e.message}。可停机后手动用 ${jar} 替换 ${target}")
    }

    report("restart", -1, "已替换，正在重启")
    runCatching { deleteRecursively(stagedDir) }
    logger.warn { "Applying upgrade $currentVersion -> $version, restart scheduled" }
    scheduleRestart()
    return UpgradeApply(applied = true, version = version, previousVersion = currentVersion, restartMode = if (isContainerized()) "container" else "manual")
  }

  fun rollback(): UpgradeApply {
    if (!inspectGuard.tryLock()) throw IllegalStateException("另一个升级任务正在进行，请稍候")
    try {
      return rollbackLocked()
    } finally {
      inspectGuard.unlock()
    }
  }

  private fun rollbackLocked(): UpgradeApply {
    val latest = latestBackup() ?: throw IllegalStateException("没有可用的备份")
    val target = currentJar() ?: throw IllegalStateException("无法定位当前运行的 jar")
    val temp = target.resolveSibling(target.fileName.toString() + ".new")
    try {
      report("copy", 0, "正在恢复备份文件")
      copyWithProgress(latest, temp, "copy", 0, 95, "正在恢复备份文件")
      Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (e: Exception) {
      runCatching { Files.deleteIfExists(temp) }
      report("error", 0, e.message ?: "回退失败")
      throw IllegalStateException("回滚失败：${e.message}")
    }
    report("restart", -1, "已恢复，正在重启")
    val restored = latest.fileName.toString().removePrefix("lantai-").removeSuffix(".jar")
    scheduleRestart()
    return UpgradeApply(applied = true, version = restored, previousVersion = currentVersion, restartMode = if (isContainerized()) "container" else "manual")
  }

  private fun copyWithProgress(
    source: Path,
    target: Path,
    phase: String,
    from: Int,
    to: Int,
    label: String,
  ) {
    val total = Files.size(source).coerceAtLeast(1L)
    var read = 0L
    var mark = 0L
    Files.newInputStream(source).use { ins ->
      Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING).use { os ->
        val buf = ByteArray(256 * 1024)
        while (true) {
          val n = ins.read(buf)
          if (n <= 0) break
          os.write(buf, 0, n)
          read += n
          if (read - mark >= PROGRESS_STEP) {
            mark = read
            val ratio = (read * 100 / total).toInt().coerceIn(0, 100)
            report(phase, from + (to - from) * ratio / 100, label)
          }
        }
      }
    }
    report(phase, to, label)
  }

  private fun backupCurrent(target: Path) {
    Files.createDirectories(backupDir)
    val backup = backupDir.resolve("lantai-$currentVersion.jar")
    val temp = backupDir.resolve("lantai-$currentVersion.jar.tmp")
    runCatching { Files.deleteIfExists(temp) }
    copyWithProgress(target, temp, "backup", 0, 40, "正在备份当前版本")
    Files.move(temp, backup, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    val removed = pruneBackups(backupDir, backup)
    if (removed > 0) logger.warn { "Pruned $removed stale upgrade backup file(s), kept ${backup.fileName}" }
  }

  private fun scheduleRestart() {
    Thread {
      Thread.sleep(1500)
      logger.warn { "Exiting for upgrade restart" }
      exitProcess(0)
    }.apply {
      isDaemon = false
      name = "lantai-upgrade-restart"
    }.start()
  }

  private fun stagedJar(): Path {
    if (!Files.isDirectory(stagedDir)) throw IllegalStateException("没有待安装的升级包")
    val manifest = readManifest()
    val named = manifest.fileName?.takeIf { it.isNotBlank() }?.let { stagedDir.resolve(it) }
    if (named != null && Files.isRegularFile(named)) return named
    val jars =
      Files.list(stagedDir).use { s ->
        s.filter { it.fileName.toString().endsWith(".jar") }.toList()
      }
    return jars.singleOrNull() ?: throw IllegalStateException("升级包内应有且仅有一个 jar")
  }

  private fun readManifest(): UpgradeManifest = readManifestOrNull() ?: throw IllegalStateException("缺少 manifest.json")

  private fun readManifestOrNull(): UpgradeManifest? {
    val file = stagedDir.resolve("manifest.json")
    if (!Files.isRegularFile(file)) return null
    return runCatching { objectMapper.readValue(Files.readString(file), UpgradeManifest::class.java) }.getOrNull()
  }

  private fun jarStructureOk(jar: Path): Boolean =
    runCatching {
      var hasBootInf = false
      ZipInputStream(Files.newInputStream(jar)).use { zis ->
        while (true) {
          val e = zis.nextEntry ?: break
          if (e.name.startsWith("BOOT-INF/")) {
            hasBootInf = true
            break
          }
        }
      }
      hasBootInf
    }.getOrDefault(false)

  private fun unzip(
    input: CountingInputStream,
    target: Path,
    compressed: Long,
  ) {
    var written = 0L
    var entries = 0
    var mark = 0L
    val zis = ZipInputStream(input)
    try {
      while (true) {
        val entry = zis.nextEntry ?: break
        val name = entry.name.replace('\\', '/')
        if (name.startsWith("/") || name.contains("..")) throw IllegalArgumentException("包内路径非法：$name")
        val out = target.resolve(name).normalize()
        if (!out.startsWith(target)) throw IllegalArgumentException("包内路径非法：$name")
        if (entry.isDirectory) {
          Files.createDirectories(out)
          continue
        }
        entries++
        if (entries > 2000) throw IllegalArgumentException("包内文件过多")
        Files.createDirectories(out.parent)
        Files.newOutputStream(out).use { os ->
          val buf = ByteArray(64 * 1024)
          while (true) {
            val n = zis.read(buf)
            if (n <= 0) break
            written += n
            if (written > 400L * 1024 * 1024) throw IllegalArgumentException("解压后体积超过 400MB")
            os.write(buf, 0, n)
            if (input.count - mark >= PROGRESS_STEP) {
              mark = input.count
              val ratio = (input.count * 100 / compressed).toInt().coerceIn(0, 99)
              report("unzip", ratio, "正在解压 " + formatSize(written))
            }
          }
        }
      }
    } finally {
      runCatching { zis.closeEntry() }
    }
  }

  private fun sha256(
    path: Path,
    onProgress: (Long) -> Unit = {},
  ): String {
    val md = MessageDigest.getInstance("SHA-256")
    var read = 0L
    var mark = 0L
    Files.newInputStream(path).use { ins ->
      val buf = ByteArray(64 * 1024)
      while (true) {
        val n = ins.read(buf)
        if (n <= 0) break
        md.update(buf, 0, n)
        read += n
        if (read - mark >= PROGRESS_STEP) {
          mark = read
          onProgress(read)
        }
      }
    }
    onProgress(read)
    return md.digest().joinToString("") { "%02x".format(it) }
  }

  private fun deleteRecursively(path: Path) {
    if (!Files.exists(path)) return
    Files.walk(path).use { s ->
      s.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } }
    }
  }

  companion object {
    fun backupVersion(
      backup: Path?,
      currentVersion: String,
    ): String? =
      backup
        ?.fileName
        ?.toString()
        ?.removePrefix("lantai-")
        ?.removeSuffix(".jar")
        ?.takeIf { it != currentVersion }

    fun pruneBackups(
      dir: Path,
      keep: Path,
    ): Int {
      if (!Files.isDirectory(dir)) return 0
      val keepPath = keep.toAbsolutePath().normalize()
      var removed = 0
      Files.list(dir).use { s ->
        s
          .filter { p ->
            val name = p.fileName.toString()
            (name.endsWith(".jar") || name.endsWith(".tmp")) && p.toAbsolutePath().normalize() != keepPath
          }.forEach { p ->
            if (runCatching { Files.deleteIfExists(p) }.getOrDefault(false)) removed++
          }
      }
      return removed
    }
  }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class UpgradeManifest(
  val product: String? = null,
  val version: String? = null,
  val builtAt: String? = null,
  val sha256: String? = null,
  val fileName: String? = null,
  val minVersion: String? = null,
  val notes: String? = null,
)

data class UpgradeStatus(
  val version: String,
  val jarPath: String?,
  val jarWritable: Boolean,
  val containerized: Boolean,
  val restartMode: String,
  val backupVersion: String?,
  val stagedVersion: String?,
)

data class UpgradeInspect(
  val version: String,
  val currentVersion: String,
  val fileName: String,
  val sizeBytes: Long,
  val sha256: String,
  val builtAt: String?,
  val notes: String?,
  val containerized: Boolean,
  val restartMode: String,
)

data class UpgradeApply(
  val applied: Boolean,
  val version: String,
  val previousVersion: String,
  val restartMode: String,
)

data class UpgradeProgress(
  val idle: Boolean = true,
  val phase: String = "",
  val pct: Int = 0,
  val label: String = "",
)
