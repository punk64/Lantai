package org.lantai.interfaces.api.rest

import com.fasterxml.jackson.databind.ObjectMapper
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.lantai.infrastructure.configuration.LantaiProperties
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Comparator
import java.util.Properties
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.sql.DataSource

@RestController
@RequestMapping("api/lantai/v1/system/archive", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Lantai System Archive")
class LantaiSystemArchiveController(
  private val lantaiProperties: LantaiProperties,
  @Qualifier("sqliteDataSourceRW") private val mainDataSource: DataSource,
  @Qualifier("tasksDataSourceRW") private val tasksDataSource: DataSource,
  @Qualifier("sessionsDataSource") private val sessionsDataSource: ObjectProvider<DataSource>,
) {
  private val exportJob = AtomicReference(ExportJob())
  private val exportBusy = AtomicBoolean(false)
  private val importProgress = AtomicReference(ImportProgress())
  private val importBusy = AtomicBoolean(false)
  private val exportPool: ExecutorService =
    Executors.newSingleThreadExecutor { runnable ->
      Thread(runnable, "lantai-archive-export").apply { isDaemon = true }
    }

  @PostMapping("export")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Start generating a backup archive in the background")
  fun export(): LantaiArchiveJobDto {
    if (!exportBusy.compareAndSet(false, true)) {
      throw ResponseStatusException(HttpStatus.CONFLICT, "正在生成备份，请等当前任务结束")
    }
    val started = Instant.now()
    exportJob.set(ExportJob(running = true, phase = "prepare", pct = 3, label = "准备打包目录", startedAt = started))
    exportPool.submit {
      try {
        val dto = pack()
        exportJob.set(ExportJob(running = false, phase = "done", pct = 100, label = "备份已生成", startedAt = started, result = dto))
      } catch (e: Exception) {
        exportJob.set(
          ExportJob(
            running = false,
            phase = "error",
            pct = 0,
            label = "生成失败",
            startedAt = started,
            error = e.message ?: e.javaClass.simpleName,
          ),
        )
      } finally {
        exportBusy.set(false)
      }
    }
    return exportJob.get().toDto()
  }

  @GetMapping("export/status")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Report the background backup generation progress")
  fun exportStatus(): LantaiArchiveJobDto = exportJob.get().toDto()

  @DeleteMapping("archives/{name}")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Delete a generated backup archive")
  fun deleteArchive(
    @PathVariable name: String,
  ): ResponseEntity<Void> {
    if (!ARCHIVE_NAME.matches(name)) throw ResponseStatusException(HttpStatus.NOT_FOUND)
    val dir = archiveDir()
    val path = dir.resolve(name).normalize()
    if (!path.startsWith(dir) || !Files.isRegularFile(path)) throw ResponseStatusException(HttpStatus.NOT_FOUND)
    Files.delete(path)
    return ResponseEntity.noContent().build()
  }

  private fun pack(): LantaiArchiveDto {
    step("prepare", 3, "准备打包目录")
    val stamp = STAMP.format(LocalDateTime.now())
    val dir = archiveDir()
    Files.createDirectories(dir)
    val target = dir.resolve("lantai-backup-$stamp.zip")
    val work = Files.createTempDirectory("lantai-archive-")
    val entries: List<LantaiArchiveEntry>
    val warnings = mutableListOf<String>()
    try {
      val dbDir = work.resolve("db")
      val configRoot = configDir()
      step("database", 12, "快照主库")
      snapshot("database", lantaiProperties.database.file, mainDataSource, dbDir)
      step("tasks", 24, "快照任务库")
      snapshot("tasks", lantaiProperties.tasksDb.file, tasksDataSource, dbDir)
      step("sessions", 32, "快照会话库")
      sessionsDataSource.ifAvailable { snapshot("sessions", lantaiProperties.sessionsDb.file, it, dbDir) }
      step("profile", 42, "收集昵称与头像")
      copyTree(configRoot.resolve("profile"), work.resolve("config/profile"))
      step("config", 50, "收集配置文件")
      yamlFiles(configRoot).forEach {
        val dest = work.resolve("config").resolve(it.fileName)
        Files.createDirectories(dest.parent)
        Files.copy(it, dest, StandardCopyOption.REPLACE_EXISTING)
      }
      step("fonts", 56, "收集扩展字体")
      copyTree(configRoot.resolve("fonts"), work.resolve("config/fonts"))
      step("plugins", 64, "收集插件与插件数据")
      copyPlugins(configRoot.resolve("plugins"), work.resolve("config/plugins"))
      step("manifest", 86, "计算校验和")
      entries = collect(work)
      writeManifest(work, entries, warnings)
      step("pack", 94, "压缩写入")
      zipTree(work, target)
    } finally {
      deleteTree(work)
    }
    step("done", 100, "备份已生成")
    return LantaiArchiveDto(
      name = target.fileName.toString(),
      bytes = Files.size(target),
      createdAt = Instant.now(),
      entries = entries.size,
      pending = pendingStamp() != null,
      warnings = warnings,
    )
  }

  private fun step(phase: String, pct: Int, label: String) {
    exportJob.updateAndGet { it.copy(running = true, phase = phase, pct = pct, label = label) }
  }

  @GetMapping("archives")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "List the backup archives on disk")
  fun archives(): List<LantaiArchiveDto> {
    val dir = archiveDir()
    if (!Files.isDirectory(dir)) return emptyList()
    return Files.list(dir).use { stream ->
      stream
        .filter { Files.isRegularFile(it) && ARCHIVE_NAME.matches(it.fileName.toString()) }
        .sorted(Comparator.comparing<Path, Long> { Files.getLastModifiedTime(it).toMillis() }.reversed())
        .map {
          LantaiArchiveDto(
            name = it.fileName.toString(),
            bytes = Files.size(it),
            createdAt = Files.getLastModifiedTime(it).toInstant(),
            entries = 0,
            pending = pendingStamp() != null,
          )
        }
        .toList()
    }
  }

  @GetMapping("download/{name}", produces = [MediaType.APPLICATION_OCTET_STREAM_VALUE])
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Download a backup archive")
  fun download(
    @PathVariable name: String,
  ): ResponseEntity<Resource> {
    if (!ARCHIVE_NAME.matches(name)) throw ResponseStatusException(HttpStatus.NOT_FOUND)
    val path = archiveDir().resolve(name)
    if (!Files.isRegularFile(path)) throw ResponseStatusException(HttpStatus.NOT_FOUND)
    return ResponseEntity
      .ok()
      .contentType(MediaType.APPLICATION_OCTET_STREAM)
      .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$name\"")
      .body(FileSystemResource(path))
  }

  @PostMapping("import", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Stage a backup archive and apply it on the next start")
  fun importArchive(
    @RequestPart("file") file: MultipartFile,
  ): LantaiRestoreStatusDto {
    if (file.isEmpty) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "备份包是空的")
    if (!importBusy.compareAndSet(false, true)) {
      throw ResponseStatusException(HttpStatus.CONFLICT, "正在接收另一个备份包，请等当前上传结束")
    }
    val stamp = STAMP.format(LocalDateTime.now())
    val staging = restoreDir().resolve(stamp)
    val payload = staging.resolve("payload")
    val upload = staging.resolve("upload.zip")
    val total = file.size.coerceAtLeast(1L)
    var entries = 0
    importProgress.set(ImportProgress(running = true, phase = "copy", pct = 0, done = 0, total = total, label = "正在接收备份包"))
    try {
      Files.createDirectories(staging)
      var copied = 0L
      var mark = 0L
      file.inputStream.use { input ->
        Files.newOutputStream(upload, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING).use { output ->
          val buffer = ByteArray(256 * 1024)
          while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            output.write(buffer, 0, read)
            copied += read
            if (copied - mark >= IMPORT_STEP) {
              mark = copied
              val pct = (copied * 100 / total).toInt().coerceIn(0, 100)
              importProgress.set(ImportProgress(true, "copy", pct, copied, total, "正在接收备份包"))
            }
          }
        }
      }
      entries = zipEntryCount(upload)
      importProgress.set(ImportProgress(true, "unzip", 0, 0, entries.toLong(), "正在解包"))
      unzip(upload, payload) { done ->
        val pct = (done * 100 / entries.coerceAtLeast(1)).coerceIn(0, 100)
        importProgress.set(ImportProgress(true, "unzip", pct, done.toLong(), entries.toLong(), "正在解包"))
      }
      importProgress.set(ImportProgress(true, "verify", 0, 0, 0, "正在校验"))
      if (!Files.isRegularFile(payload.resolve("db").resolve("database.sqlite"))) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, "备份包里没有 db/database.sqlite")
      }
      Files.deleteIfExists(upload)
      writePending(stamp)
      importProgress.set(ImportProgress(false, "done", 100, entries.toLong(), entries.toLong(), "备份已就位"))
    } catch (e: Exception) {
      deleteTree(staging)
      importProgress.set(ImportProgress(false, "error", 0, 0, 0, "导入失败", e.message ?: e.javaClass.simpleName))
      throw e
    } finally {
      importBusy.set(false)
    }
    return status()
  }

  @GetMapping("import/status")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Report the backup upload and validation progress")
  fun importStatus(): LantaiImportProgressDto = importProgress.get().toDto()

  @GetMapping("restore")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Retrieve the pending restore state")
  fun status(): LantaiRestoreStatusDto {
    val stamp = pendingStamp()
    return LantaiRestoreStatusDto(
      pending = stamp != null,
      staging = stamp,
      createdAt = stamp?.let { runCatching { LocalDateTime.parse(it, STAMP).atZone(ZoneId.systemDefault()).toInstant() }.getOrNull() },
    )
  }

  private fun snapshot(label: String, file: String, dataSource: DataSource, dir: Path) {
    if (file.isBlank() || file.contains(":memory:") || file.contains("mode=memory")) return
    if (!Files.exists(Paths.get(file))) return
    Files.createDirectories(dir)
    val target = dir.resolve("$label.sqlite")
    Files.deleteIfExists(target)
    val literal = target.toString().replace('\\', '/').replace("'", "''")
    dataSource.connection.use { connection ->
      connection.createStatement().use { statement -> statement.execute("VACUUM INTO '$literal'") }
    }
  }

  private fun copyTree(
    source: Path,
    target: Path,
    skip: (String) -> Boolean = { false },
  ) {
    if (!Files.isDirectory(source)) return
    Files.walk(source).use { stream ->
      stream.filter { Files.isRegularFile(it) }.forEach { path ->
        val relative = source.relativize(path).toString().replace('\\', '/')
        if (skip(relative)) return@forEach
        val dest = target.resolve(source.relativize(path))
        Files.createDirectories(dest.parent)
        Files.copy(path, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
      }
    }
  }

  private fun copyPlugins(source: Path, target: Path) {
    if (!Files.isDirectory(source)) return
    copyTree(source, target) { relative -> pluginVolatile(relative) }
  }

  private fun pluginVolatile(relative: String): Boolean {
    val parts = relative.split('/')
    if (parts.firstOrNull()?.startsWith("_") == true) return true
    if (parts.any { it in PLUGIN_VOLATILE_DIRS }) return true
    return parts.last().endsWith(".log")
  }

  private fun yamlFiles(root: Path): List<Path> =
    root.toFile().listFiles { f -> f.isFile && (f.name.endsWith(".yml") || f.name.endsWith(".yaml")) }
      ?.map { it.toPath() }
      .orEmpty()

  private fun collect(root: Path): List<LantaiArchiveEntry> =
    Files.walk(root).use { stream ->
      stream
        .filter { Files.isRegularFile(it) }
        .sorted()
        .map {
          LantaiArchiveEntry(
            path = root.relativize(it).toString().replace('\\', '/'),
            bytes = Files.size(it),
            sha256 = sha256(it),
            exec = POSIX && Files.isExecutable(it),
          )
        }
        .toList()
    }

  private fun writeManifest(
    work: Path,
    entries: List<LantaiArchiveEntry>,
    warnings: List<String>,
  ) {
    val payload =
      linkedMapOf<String, Any>(
        "format" to 2,
        "app" to "lantai",
        "version" to APP_VERSION,
        "createdAt" to Instant.now().toString(),
        "platform" to System.getProperty("os.name").orEmpty(),
        "configDir" to configDir().toString(),
        "warnings" to warnings,
        "entries" to
          entries.map {
            val item = linkedMapOf<String, Any>("path" to it.path, "bytes" to it.bytes, "sha256" to it.sha256)
            if (it.exec) item["exec"] = true
            item
          },
      )
    Files.write(work.resolve("manifest.json"), ObjectMapper().writeValueAsBytes(payload))
  }

  private fun zipTree(root: Path, target: Path) {
    ZipOutputStream(BufferedOutputStream(Files.newOutputStream(target))).use { zos ->
      Files.walk(root).use { stream ->
        stream.filter { Files.isRegularFile(it) }.sorted().forEach { path ->
          zos.putNextEntry(ZipEntry(root.relativize(path).toString().replace('\\', '/')))
          Files.copy(path, zos)
          zos.closeEntry()
        }
      }
    }
  }

  private fun unzip(zip: Path, target: Path, onEntry: (Int) -> Unit = {}) {
    Files.createDirectories(target)
    var done = 0
    ZipInputStream(Files.newInputStream(zip)).use { zis ->
      var entry: ZipEntry? = zis.nextEntry
      while (entry != null) {
        val dest = target.resolve(entry.name).normalize()
        if (!dest.startsWith(target)) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "备份包内路径非法")
        if (entry.isDirectory) {
          Files.createDirectories(dest)
        } else {
          Files.createDirectories(dest.parent)
          Files.copy(zis, dest, StandardCopyOption.REPLACE_EXISTING)
        }
        zis.closeEntry()
        done += 1
        onEntry(done)
        entry = zis.nextEntry
      }
    }
  }

  private fun zipEntryCount(zip: Path): Int =
    runCatching { ZipFile(zip.toFile()).use { it.size() } }.getOrDefault(1)

  private fun writePending(stamp: String) {
    val props = Properties()
    props.setProperty("staging", stamp)
    props.setProperty("created", Instant.now().toString())
    val root = configDir()
    Files.createDirectories(root)
    Files.newOutputStream(root.resolve(PENDING_FILE)).use { props.store(it, "lantai restore pending") }
  }

  private fun pendingStamp(): String? {
    val file = configDir().resolve(PENDING_FILE)
    if (!Files.isRegularFile(file)) return null
    return runCatching {
      val props = Properties()
      Files.newInputStream(file).use { props.load(it) }
      props.getProperty("staging")?.takeIf { STAMP_PATTERN.matches(it) }
    }.getOrNull()
  }

  private fun configDir(): Path = Paths.get(lantaiProperties.configDir ?: ".").toAbsolutePath().normalize()

  private fun archiveDir(): Path = configDir().resolve("backups").resolve("archive")

  private fun restoreDir(): Path = configDir().resolve("restore-staging")

  private fun deleteTree(dir: Path) {
    if (!Files.exists(dir)) return
    Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } } }
  }

  private fun sha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
      val buffer = ByteArray(1 shl 16)
      while (true) {
        val read = input.read(buffer)
        if (read <= 0) break
        digest.update(buffer, 0, read)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  companion object {
    private const val PENDING_FILE = "restore.pending"
    private const val IMPORT_STEP = 4L * 1024 * 1024
    private val PLUGIN_VOLATILE_DIRS = setOf("logs", "cache", "tmp", "temp", "backups", "downloads")
    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
    private val STAMP_PATTERN = Regex("\\d{8}-\\d{6}-\\d{3}")
    private val ARCHIVE_NAME = Regex("lantai-backup-[\\d-]+\\.zip")
    private val POSIX: Boolean = FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
    private val APP_VERSION: String =
      LantaiSystemArchiveController::class.java.`package`?.implementationVersion ?: "0"
  }
}

data class LantaiArchiveDto(
  val name: String,
  val bytes: Long,
  val createdAt: Instant,
  val entries: Int,
  val pending: Boolean,
  val warnings: List<String> = emptyList(),
)

data class LantaiArchiveJobDto(
  val running: Boolean,
  val phase: String?,
  val pct: Int,
  val label: String?,
  val startedAt: Instant?,
  val error: String?,
  val result: LantaiArchiveDto?,
)

private data class ExportJob(
  val running: Boolean = false,
  val phase: String? = null,
  val pct: Int = 0,
  val label: String? = null,
  val startedAt: Instant? = null,
  val error: String? = null,
  val result: LantaiArchiveDto? = null,
) {
  fun toDto(): LantaiArchiveJobDto =
    LantaiArchiveJobDto(
      running = running,
      phase = phase,
      pct = pct,
      label = label,
      startedAt = startedAt,
      error = error,
      result = result,
    )
}

data class LantaiArchiveEntry(
  val path: String,
  val bytes: Long,
  val sha256: String,
  val exec: Boolean = false,
)

data class LantaiRestoreStatusDto(
  val pending: Boolean,
  val staging: String?,
  val createdAt: Instant?,
)

data class LantaiImportProgressDto(
  val running: Boolean,
  val phase: String?,
  val pct: Int,
  val done: Long,
  val total: Long,
  val label: String?,
  val error: String?,
)

private data class ImportProgress(
  val running: Boolean = false,
  val phase: String? = null,
  val pct: Int = 0,
  val done: Long = 0,
  val total: Long = 0,
  val label: String? = null,
  val error: String? = null,
) {
  fun toDto(): LantaiImportProgressDto =
    LantaiImportProgressDto(
      running = running,
      phase = phase,
      pct = pct,
      done = done,
      total = total,
      label = label,
      error = error,
    )
}
