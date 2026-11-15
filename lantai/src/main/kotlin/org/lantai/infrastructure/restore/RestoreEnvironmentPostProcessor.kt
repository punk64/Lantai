package org.lantai.infrastructure.restore

import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.Ordered
import org.springframework.core.env.ConfigurableEnvironment
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.sql.DriverManager
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Comparator
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
      val executables = verifyPayload(payload)
      replaceDatabase(environment, "lantai.database.file", payload.resolve("db/database.sqlite"), backup)
      replaceDatabase(environment, "lantai.tasks-db.file", payload.resolve("db/tasks.sqlite"), backup)
      replaceDatabase(environment, "lantai.sessions-db.file", payload.resolve("db/sessions.sqlite"), backup)
      copyTree(payload.resolve("config/profile"), root.resolve("profile"))
      copyTree(payload.resolve("config/fonts"), root.resolve("fonts"))
      val plugins = payload.resolve("config/plugins")
      restorePlugins(plugins, root.resolve("plugins"), backup)
      if (Files.isDirectory(plugins)) repairPluginInstances(environment, root)
      applyExecutables(root.resolve("plugins"), "config/plugins", executables)
      if (executables.isEmpty()) {
        sniffExecutables(root.resolve("plugins"))
      }
      Files.deleteIfExists(pending)
      note(root, "还原完成，包时间戳 $stamp，原数据已备份为 pre-restore-$backup")
    } catch (e: Exception) {
      note(root, "还原失败（包 $stamp）：${e.message}")
    }
  }

  private fun restorePlugins(source: Path, target: Path, backup: String) {
    if (!Files.isDirectory(source)) return
    if (Files.exists(target)) {
      val aside = target.resolveSibling("${target.fileName}.pre-restore-$backup")
      deleteTree(aside)
      val moved = runCatching { Files.move(target, aside) }.isSuccess
      if (!moved) deleteTree(target)
    }
    copyTree(source, target)
  }

  private fun repairPluginInstances(environment: ConfigurableEnvironment, root: Path) {
    val raw = environment.getProperty("lantai.database.file") ?: return
    val db = Paths.get(environment.resolvePlaceholders(raw)).toAbsolutePath().normalize()
    if (!Files.isRegularFile(db)) return
    val plugins = root.resolve("plugins")
    runCatching { Class.forName("org.sqlite.JDBC") }
    runCatching {
      DriverManager.getConnection("jdbc:sqlite:${db.toString().replace('\\', '/')}").use { connection ->
        val rows = mutableListOf<Triple<String, String, String>>()
        connection.createStatement().use { statement ->
          statement.executeQuery("SELECT ID, PLUGIN_ID, VERSION FROM PLUGIN_INSTANCE WHERE MODE = 'MANAGED'").use { rs ->
            while (rs.next()) rows += Triple(rs.getString(1), rs.getString(2), rs.getString(3))
          }
        }
        connection.prepareStatement(
          "UPDATE PLUGIN_INSTANCE SET HOME_DIR = ?, PID = NULL, " +
            "STATE = CASE WHEN ENABLED = 0 THEN 'DISABLED' ELSE 'PENDING' END, STATUS_MESSAGE = NULL WHERE ID = ?",
        ).use { update ->
          rows.forEach { (id, pluginId, version) ->
            val home = plugins.resolve(pluginId).resolve(version).normalize()
            update.setString(1, home.toString())
            update.setString(2, id)
            update.addBatch()
          }
          update.executeBatch()
        }
      }
    }.onFailure { note(root, "插件路径修正失败：${it.message}") }
  }

  private fun deleteTree(dir: Path) {
    if (!Files.exists(dir)) return
    runCatching {
      Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } } }
    }
  }

  private fun applyExecutables(target: Path, prefix: String, executables: List<String>) {
    if (!POSIX || executables.isEmpty() || !Files.isDirectory(target)) return
    val base = "$prefix/"
    executables.forEach { rel ->
      if (!rel.startsWith(base)) return@forEach
      val path = target.resolve(rel.removePrefix(base)).normalize()
      if (!path.startsWith(target) || !Files.isRegularFile(path)) return@forEach
      grantExecutable(path)
    }
  }

  private fun sniffExecutables(target: Path) {
    if (!POSIX || !Files.isDirectory(target)) return
    Files.walk(target).use { stream ->
      stream.filter { Files.isRegularFile(it) && !Files.isExecutable(it) }.forEach { path ->
        if (looksExecutable(path)) grantExecutable(path)
      }
    }
  }

  private fun looksExecutable(path: Path): Boolean =
    runCatching {
      val head = Files.newInputStream(path).use { input -> ByteArray(4).also { input.read(it) } }
      val shebang = head[0] == '#'.code.toByte() && head[1] == '!'.code.toByte()
      val elf = head[0] == 0x7F.toByte() && head[1] == 'E'.code.toByte() && head[2] == 'L'.code.toByte() && head[3] == 'F'.code.toByte()
      shebang || elf
    }.getOrDefault(false)

  private fun grantExecutable(path: Path) {
    runCatching {
      val perms = Files.getPosixFilePermissions(path).toMutableSet()
      val extra =
        buildList {
          if (PosixFilePermission.OWNER_READ in perms) add(PosixFilePermission.OWNER_EXECUTE)
          if (PosixFilePermission.GROUP_READ in perms) add(PosixFilePermission.GROUP_EXECUTE)
          if (PosixFilePermission.OTHERS_READ in perms) add(PosixFilePermission.OTHERS_EXECUTE)
          if (isEmpty()) add(PosixFilePermission.OWNER_EXECUTE)
        }
      if (!perms.containsAll(extra)) Files.setPosixFilePermissions(path, perms + extra)
    }
  }

  private fun verifyPayload(payload: Path): List<String> {
    val manifest = payload.resolve("manifest.json")
    if (!Files.isRegularFile(manifest)) throw IllegalStateException("备份包缺少 manifest.json")
    val node = runCatching { MAPPER.readTree(Files.readString(manifest)) }.getOrElse { throw IllegalStateException("manifest.json 无法解析") }
    val entries = node.path("entries")
    if (!entries.isArray || entries.size() == 0) throw IllegalStateException("manifest.json 缺少文件清单")
    val executables = mutableListOf<String>()
    entries.forEach { entry ->
      val rel = entry.path("path").asText("").trim()
      if (rel.isEmpty()) throw IllegalStateException("manifest.json 条目缺少路径")
      val file = payload.resolve(rel).normalize()
      if (!file.startsWith(payload)) throw IllegalStateException("manifest.json 路径越界：$rel")
      if (!Files.isRegularFile(file)) throw IllegalStateException("备份包缺少文件：$rel")
      val expected = entry.path("sha256").asText("").trim()
      if (expected.isEmpty()) throw IllegalStateException("manifest.json 条目缺少校验值：$rel")
      if (!sha256(file).equals(expected, ignoreCase = true)) throw IllegalStateException("备份包文件校验失败：$rel")
      if (entry.path("exec").asBoolean(false)) executables += rel
    }
    return executables
  }

  private fun sha256(file: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(file).use { input ->
      val buffer = ByteArray(64 * 1024)
      while (true) {
        val read = input.read(buffer)
        if (read <= 0) break
        digest.update(buffer, 0, read)
      }
    }
    return digest.digest().joinToString("") { ((it.toInt() and 0xFF) + 0x100).toString(16).substring(1) }
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
          Files.copy(path, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
        }
      }
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
    private val MAPPER = com.fasterxml.jackson.databind.ObjectMapper()
    private val POSIX: Boolean = FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
    private const val PENDING_FILE = "restore.pending"
    private const val LOG_FILE = "restore.log"
    private val STAMP: Regex = Regex("\\d{8}-\\d{6}-\\d{3}")
    private val STAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
  }
}
