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
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
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
    try {
      val dbDir = work.resolve("db")
      val configRoot = configDir()
      step("database", 12, "快照主库")
      snapshot("database", lantaiProperties.database.file, mainDataSource, dbDir)
      step("tasks", 28, "快照任务库")
      snapshot("tasks", lantaiProperties.tasksDb.file, tasksDataSource, dbDir)
      step("sessions", 38, "快照会话库")
      sessionsDataSource.ifAvailable { snapshot("sessions", lantaiProperties.sessionsDb.file, it, dbDir) }
      step("profile", 52, "收集昵称与头像")
      copyTree(configRoot.resolve("profile"), work.resolve("config/profile"))
      step("runtime", 62, "收集运行时与插件")
      copyTree(configRoot.resolve("runtime"), work.resolve("config/runtime"))
      step("config", 70, "收集配置文件")
      yamlFiles(configRoot).forEach {
        val dest = work.resolve("config").resolve(it.fileName)
        Files.createDirectories(dest.parent)
        Files.copy(it, dest, StandardCopyOption.REPLACE_EXISTING)
      }
      step("suwayomi", 78, "收集 suwayomi 数据")
      copyTree(configRoot.resolveSibling("suwayomi"), work.resolve("suwayomi"))
      step("manifest", 86, "计算校验和")
      entries = collect(work)
      writeManifest(work, entries)
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
    val stamp = STAMP.format(LocalDateTime.now())
    val staging = restoreDir().resolve(stamp)
    Files.createDirectories(staging)
    val payload = staging.resolve("payload")
    val upload = staging.resolve("upload.zip")
    try {
      file.inputStream.use { input -> Files.copy(input, upload, StandardCopyOption.REPLACE_EXISTING) }
      unzip(upload, payload)
      if (!Files.isRegularFile(payload.resolve("db").resolve("database.sqlite"))) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, "备份包里没有 db/database.sqlite")
      }
      Files.deleteIfExists(upload)
      writePending(stamp)
    } catch (e: Exception) {
      deleteTree(staging)
      throw e
    }
    return status()
  }

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

  private fun copyTree(source: Path, target: Path) {
    if (!Files.isDirectory(source)) return
    Files.walk(source).use { stream ->
      stream.filter { Files.isRegularFile(it) }.forEach { path ->
        val dest = target.resolve(source.relativize(path))
        Files.createDirectories(dest.parent)
        Files.copy(path, dest, StandardCopyOption.REPLACE_EXISTING)
      }
    }
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
          )
        }
        .toList()
    }

  private fun writeManifest(work: Path, entries: List<LantaiArchiveEntry>) {
    val payload =
      linkedMapOf<String, Any>(
        "format" to 1,
        "app" to "lantai",
        "version" to APP_VERSION,
        "createdAt" to Instant.now().toString(),
        "platform" to System.getProperty("os.name").orEmpty(),
        "entries" to entries.map { mapOf("path" to it.path, "bytes" to it.bytes, "sha256" to it.sha256) },
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

  private fun unzip(zip: Path, target: Path) {
    Files.createDirectories(target)
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
        entry = zis.nextEntry
      }
    }
  }

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
    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
    private val STAMP_PATTERN = Regex("\\d{8}-\\d{6}-\\d{3}")
    private val ARCHIVE_NAME = Regex("lantai-backup-[\\d-]+\\.zip")
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
)

data class LantaiRestoreStatusDto(
  val pending: Boolean,
  val staging: String?,
  val createdAt: Instant?,
)
