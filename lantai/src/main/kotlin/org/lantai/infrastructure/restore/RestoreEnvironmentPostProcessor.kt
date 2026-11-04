package org.lantai.infrastructure.restore

import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.Ordered
import org.springframework.core.env.ConfigurableEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties

class RestoreEnvironmentPostProcessor : EnvironmentPostProcessor, Ordered {
  override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE + 100

  override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
    val root =
      try {
        configDir(environment)
      } catch (_: Exception) {
        return
      }
    val pending = root.resolve(PENDING_FILE)
    if (!Files.isRegularFile(pending)) return
    val props = Properties()
    try {
      Files.newInputStream(pending).use { props.load(it) }
    } catch (e: Exception) {
      note(root, "读取 $PENDING_FILE 失败：${e.message}")
      return
    }
    val stamp = props.getProperty("staging").orEmpty()
    if (!STAMP.matches(stamp)) {
      note(root, "$PENDING_FILE 内容非法，已忽略")
      return
    }
    val payload = root.resolve("restore-staging").resolve(stamp).resolve("payload")
    if (!Files.isDirectory(payload)) {
      note(root, "待还原目录不存在：$payload")
      return
    }
    val backup = STAMP_FORMAT.format(LocalDateTime.now())
    try {
      replaceDatabase(environment, "lantai.database.file", payload.resolve("db/database.sqlite"), backup)
      replaceDatabase(environment, "lantai.tasks-db.file", payload.resolve("db/tasks.sqlite"), backup)
      replaceDatabase(environment, "lantai.sessions-db.file", payload.resolve("db/sessions.sqlite"), backup)
      copyTree(payload.resolve("config/profile"), root.resolve("profile"))
      copyTree(payload.resolve("config/runtime"), root.resolve("runtime"))
      copyConfigFiles(payload.resolve("config"), root)
      copyTree(payload.resolve("suwayomi"), root.resolveSibling("suwayomi"))
      Files.deleteIfExists(pending)
      note(root, "还原完成，包时间戳 $stamp，原数据已备份为 pre-restore-$backup")
    } catch (e: Exception) {
      note(root, "还原失败（包 $stamp）：${e.message}")
    }
  }

  private fun configDir(environment: ConfigurableEnvironment): Path {
    val raw = environment.getProperty("lantai.config-dir") ?: "${System.getProperty("user.home")}/.lantai"
    return Paths.get(environment.resolvePlaceholders(raw)).toAbsolutePath().normalize()
  }

  private fun replaceDatabase(environment: ConfigurableEnvironment, key: String, source: Path, backup: String) {
    if (!Files.isRegularFile(source)) return
    val raw = environment.getProperty(key) ?: return
    val target = Paths.get(environment.resolvePlaceholders(raw)).toAbsolutePath().normalize()
    Files.createDirectories(target.parent)
    if (Files.isRegularFile(target)) {
      Files.copy(target, target.resolveSibling("${target.fileName}.pre-restore-$backup"), StandardCopyOption.REPLACE_EXISTING)
    }
    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
    Files.deleteIfExists(target.resolveSibling("${target.fileName}-wal"))
    Files.deleteIfExists(target.resolveSibling("${target.fileName}-shm"))
  }

  private fun copyTree(source: Path, target: Path) {
    if (!Files.isDirectory(source)) return
    Files.walk(source).use { stream ->
      stream.forEach { path ->
        val dest = target.resolve(source.relativize(path).toString())
        if (Files.isDirectory(path)) {
          Files.createDirectories(dest)
        } else {
          Files.createDirectories(dest.parent)
          Files.copy(path, dest, StandardCopyOption.REPLACE_EXISTING)
        }
      }
    }
  }

  private fun copyConfigFiles(source: Path, target: Path) {
    if (!Files.isDirectory(source)) return
    Files.list(source).use { stream ->
      stream
        .filter { Files.isRegularFile(it) && YAML.matches(it.fileName.toString()) }
        .forEach { Files.copy(it, target.resolve(it.fileName.toString()), StandardCopyOption.REPLACE_EXISTING) }
    }
  }

  private fun note(root: Path, message: String) {
    runCatching {
      Files.createDirectories(root)
      Files.write(
        root.resolve(LOG_FILE),
        "[${LocalDateTime.now()}] $message\n".toByteArray(),
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND,
      )
    }
  }

  companion object {
    private const val PENDING_FILE = "restore.pending"
    private const val LOG_FILE = "restore.log"
    private val STAMP: Regex = Regex("\\d{8}-\\d{6}-\\d{3}")
    private val STAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
    private val YAML: Regex = Regex("application.*\\.ya?ml")
  }
}
