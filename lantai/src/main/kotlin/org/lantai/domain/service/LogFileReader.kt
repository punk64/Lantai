package org.lantai.domain.service

import org.lantai.infrastructure.configuration.LantaiProperties
import org.springframework.core.env.Environment
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.zip.GZIPInputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

data class LogFileInfo(
  val name: String,
  val sizeBytes: Long,
  val lastModified: LocalDateTime,
  val compressed: Boolean,
  val current: Boolean,
  val source: String = "lantai",
)

data class LogEntry(
  val time: String? = null,
  val level: String? = null,
  val thread: String? = null,
  val loggerName: String? = null,
  val message: String,
)

data class LogPage(
  val file: String,
  val nextCursor: Long,
  val hasMore: Boolean,
  val entries: List<LogEntry>,
)

@Service
class LogFileReader(
  environment: Environment,
  lantaiProperties: LantaiProperties,
  private val extraSources: List<ExtraLogSource> = emptyList(),
) {
  private val configuredLogFile: String = environment.getProperty("logging.file.name", "")

  private val logDir: Path =
    configuredLogFile
      .takeIf { it.isNotBlank() }
      ?.let { Paths.get(it).parent }
      ?: Paths.get(lantaiProperties.configDir ?: ".", "logs")

  private val currentFileName: String? =
    configuredLogFile
      .takeIf { it.isNotBlank() }
      ?.let { Paths.get(it).name }

  fun logDirectory(): Path = logDir

  fun defaultFile(): String? {
    val files = listFiles()
    return files.firstOrNull { it.current }?.name ?: files.firstOrNull()?.name
  }

  fun listFiles(): List<LogFileInfo> {
    val main =
      if (!Files.isDirectory(logDir)) {
        emptyList()
      } else {
        Files.list(logDir).use { stream ->
          stream
            .filter { it.isRegularFile() }
            .filter { FILE_NAME_PATTERN.matches(it.name) }
            .map { path ->
              LogFileInfo(
                name = path.name,
                sizeBytes = Files.size(path),
                lastModified = Files.getLastModifiedTime(path).toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime(),
                compressed = path.name.endsWith(".gz"),
                current = path.name == currentFileName,
                source = if (path.name.startsWith(EXTENSION_LOG_PREFIX)) "extensions" else "lantai",
              )
            }
            .toList()
        }
      }
    val extra =
      extraSources.mapNotNull { source ->
        val path = runCatching { source.path() }.getOrNull() ?: return@mapNotNull null
        val size = runCatching { Files.size(path) }.getOrNull() ?: return@mapNotNull null
        LogFileInfo(
          name = source.displayName,
          sizeBytes = size,
          lastModified = Files.getLastModifiedTime(path).toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime(),
          compressed = false,
          current = false,
          source = source.sourceId,
        )
      }
    return (main + extra).sortedByDescending { it.lastModified }
  }

  fun resolve(name: String): Path {
    val extra = extraSource(name)
    if (extra != null) return extra.path() ?: throw NoSuchFileException(name)
    if (!FILE_NAME_PATTERN.matches(name)) throw IllegalArgumentException("Invalid log file name")
    val path = logDir.resolve(name).normalize()
    if (path.parent?.normalize() != logDir.normalize()) throw IllegalArgumentException("Invalid log file name")
    if (!path.isRegularFile()) throw NoSuchFileException(name)
    return path
  }

  private fun extraSource(name: String): ExtraLogSource? = extraSources.firstOrNull { it.displayName == name }

  fun read(
    name: String,
    cursor: Long?,
    limit: Int?,
    level: String?,
    keyword: String?,
    from: String?,
    to: String?,
  ): LogPage {
    val path = resolve(name)
    val maxEntries = (limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
    val filter = Filter.of(level, keyword, from, to)
    val parse = parserFor(name)

    if (path.name.endsWith(".gz")) {
      val entries = readCompressed(path, maxEntries, filter)
      return LogPage(file = path.name, nextCursor = 0, hasMore = false, entries = entries)
    }

    val start = (cursor ?: 0L).coerceAtLeast(0L)
    val length = Files.size(path)
    if (start >= length) return LogPage(file = path.name, nextCursor = length, hasMore = false, entries = emptyList())

    val matched = mutableListOf<Pair<LogEntry, Long>>()
    var scannedTo = start
    var current: LogEntry? = null
    var currentEnd = start

    RandomAccessFile(path.toFile(), "r").use { raf ->
      raf.seek(start)
      val buffer = ByteArrayOutputStream()
      var position = start
      var lineStart = start
      var scannedLines = 0
      var byte = raf.read()
      while (byte != -1 && matched.size <= maxEntries && scannedLines < MAX_SCAN_LINES) {
        position++
        if (byte.toChar() == '\n') {
          val line = buffer.toString(StandardCharsets.UTF_8).trimEnd('\r')
          buffer.reset()
          scannedLines++
          scannedTo = position
          val entry = parse(line)
          if (entry != null) {
            current?.let { if (filter.matches(it)) matched.add(it to currentEnd) }
            current = entry
            currentEnd = position
          } else if (current != null) {
            current = current.copy(message = current.message + "\n" + line)
            currentEnd = position
          }
          lineStart = position
        } else {
          buffer.write(byte)
        }
        byte = raf.read()
      }
      current?.let { if (filter.matches(it)) matched.add(it to currentEnd) }
      if (buffer.size() > 0) scannedTo = lineStart
    }

    val page = matched.take(maxEntries).map { it.first }
    val nextCursor = if (matched.size > maxEntries) matched[maxEntries - 1].second else scannedTo
    return LogPage(file = path.name, nextCursor = nextCursor, hasMore = nextCursor < length, entries = page)
  }

  private fun readCompressed(
    path: Path,
    maxEntries: Int,
    filter: Filter,
  ): List<LogEntry> {
    val matched = mutableListOf<LogEntry>()
    var current: LogEntry? = null
    var scannedLines = 0
    GZIPInputStream(Files.newInputStream(path)).bufferedReader(StandardCharsets.UTF_8).use { reader ->
      var line = reader.readLine()
      while (line != null && matched.size < maxEntries && scannedLines < MAX_SCAN_LINES) {
        scannedLines++
        val entry = parseLine(line)
        if (entry != null) {
          current?.let { if (filter.matches(it)) matched.add(it) }
          current = entry
        } else if (current != null) {
          current = current.copy(message = current.message + "\n" + line)
        }
        line = reader.readLine()
      }
    }
    current?.let { if (filter.matches(it) && matched.size < maxEntries) matched.add(it) }
    return matched
  }

  private fun parserFor(name: String): (String) -> LogEntry? {
    val extra = extraSource(name) ?: return ::parseLine
    return { line -> extra.parse(line) }
  }

  private fun parseLine(line: String): LogEntry? {
    val match = LINE_PATTERN.find(line) ?: return null
    val groups = match.groupValues
    return LogEntry(
      time = groups[1],
      level = groups[2],
      thread = groups[4].trim(),
      loggerName = groups[5],
      message = mask(groups[6]),
    )
  }

  private fun mask(text: String): String {
    var result = text
    MASK_PATTERNS.forEach { pattern ->
      result = pattern.replace(result) { it.groupValues[1] + MASK }
    }
    return result
  }

  private class Filter(
    private val minLevelIndex: Int?,
    private val keyword: String?,
    private val from: OffsetDateTime?,
    private val to: OffsetDateTime?,
  ) {
    fun matches(entry: LogEntry): Boolean {
      if (minLevelIndex != null) {
        val index = LEVELS.indexOf(entry.level ?: "")
        if (index < minLevelIndex) return false
      }
      if (keyword != null) {
        val hit =
          entry.message.contains(keyword, ignoreCase = true) ||
            entry.loggerName?.contains(keyword, ignoreCase = true) == true
        if (!hit) return false
      }
      if (from != null || to != null) {
        val time = entry.time?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() } ?: return false
        if (from != null && time.isBefore(from)) return false
        if (to != null && time.isAfter(to)) return false
      }
      return true
    }

    companion object {
      fun of(
        level: String?,
        keyword: String?,
        from: String?,
        to: String?,
      ): Filter {
        val levelIndex =
          level
            ?.trim()
            ?.uppercase()
            ?.takeIf { it.isNotEmpty() }
            ?.let { LEVELS.indexOf(it) }
            ?.takeIf { it >= 0 }
        return Filter(
          minLevelIndex = levelIndex,
          keyword = keyword?.trim()?.takeIf { it.isNotEmpty() },
          from = from?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() },
          to = to?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() },
        )
      }
    }
  }

  companion object {
    val LEVELS = listOf("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL")
    private const val DEFAULT_LIMIT = 200
    private const val MAX_LIMIT = 2000
    private const val MAX_SCAN_LINES = 20000
    private const val MASK = "***"
    private const val EXTENSION_LOG_PREFIX = "extensions"

    private val FILE_NAME_PATTERN = Regex("""^[\w.-]+\.log(\.[\w-]+){0,3}$""")

    private val LINE_PATTERN =
      Regex(
        """^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}[.,]\d{1,6}(?:[+-]\d{2}:?\d{2}|Z)?)\s+(\w+)\s+(\d+)\s+---\s+\[([^\]]*)\]\s+(\S+)\s*:\s?(.*)$""",
      )

    private val MASK_PATTERNS =
      listOf(
        Regex("""(?i)((?:password|passwd|pwd|api[_-]?key|token|secret|authorization)\s*[=:]\s*)(\S+)"""),
        Regex("""(?i)(bearer\s+)(\S+)"""),
      )
  }
}
