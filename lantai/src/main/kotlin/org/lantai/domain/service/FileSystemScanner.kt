package org.lantai.domain.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.lantai.domain.model.Book
import org.lantai.domain.model.DirectoryNotFoundException
import org.lantai.domain.model.ScanResult
import org.lantai.domain.model.Series
import org.lantai.domain.model.Sidecar
import org.lantai.infrastructure.metadata.SeriesTitleCleaner
import org.lantai.infrastructure.sidecar.SidecarBookConsumer
import org.lantai.infrastructure.sidecar.SidecarSeriesConsumer
import org.springframework.stereotype.Service
import java.io.IOException
import java.net.URL
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.FileVisitor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.pathString
import kotlin.io.path.readAttributes
import kotlin.time.measureTime

private val logger = KotlinLogging.logger {}
private val VOLUME_SEPARATORS = charArrayOf('-', '_', '—', '–', '·', '・', ':', '：', '|', ' ')
private val VOLUME_NUMBER_PATTERN = Regex("^(第\\s*\\d+\\s*[卷册部回集]|\\d+|[Vv]ol(ume)?\\.?\\s*\\d+)$")

data class VolumeGroupingPreview(
  val dirs: Int,
  val series: Int,
  val groups: List<VolumeGroupPreview>,
)

data class VolumeGroupPreview(
  val seriesName: String,
  val path: String,
  val volumes: List<VolumePreview>,
) {
  val bookCount: Int get() = volumes.sumOf { it.bookCount }
}

data class VolumePreview(
  val name: String,
  val bookCount: Int,
)

@Service
class FileSystemScanner(
  private val sidecarBookConsumers: List<SidecarBookConsumer>,
  private val sidecarSeriesConsumers: List<SidecarSeriesConsumer>,
) {
  private data class TempSidecar(
    val name: String,
    val url: URL,
    val lastModifiedTime: LocalDateTime,
    val type: Sidecar.Type? = null,
  )

  private data class Collected(
    val pathToSeries: Map<Path, Series>,
    val pathToSeriesSidecars: Map<Path, MutableList<Sidecar>>,
    val pathToBooks: Map<Path, MutableList<Book>>,
    val pathToBookSidecars: Map<Path, MutableList<TempSidecar>>,
  )

  private data class VolumeGroup(
    val seriesPath: Path,
    val seriesName: String,
    val volumeName: String,
  )

  private val sidecarBookPrefilter = sidecarBookConsumers.flatMap { it.getSidecarBookPrefilter() }

  fun scanRootFolder(
    root: Path,
    forceDirectoryModifiedTime: Boolean = false,
    oneshotsDir: String? = null,
    scanCbx: Boolean = true,
    scanPdf: Boolean = true,
    scanEpub: Boolean = true,
    scanTxt: Boolean = true,
    directoryExclusions: Set<String> = emptySet(),
    volumeGrouping: Boolean = false,
  ): ScanResult {
    val scanForExtensions = extensionsToScan(scanCbx, scanPdf, scanEpub, scanTxt)
    logger.info { "Scanning folder: $root" }
    logger.info { "Scan for extensions: $scanForExtensions" }
    logger.info { "Excluded directory patterns: $directoryExclusions" }
    logger.info { "Force directory modified time: $forceDirectoryModifiedTime" }
    logger.info { "Volume grouping: $volumeGrouping" }

    if (!(Files.isDirectory(root) && Files.isReadable(root)))
      throw DirectoryNotFoundException("Folder is not accessible: $root", "ERR_1016")

    val scannedSeries = mutableMapOf<Series, List<Book>>()
    val scannedSidecars = mutableListOf<Sidecar>()

    measureTime {
      val collected = collect(root, scanForExtensions, directoryExclusions)
      val groups = if (volumeGrouping) resolveVolumeGroups(root, collected.pathToBooks.keys) else emptyMap()
      val seriesByPath = mutableMapOf<Path, Series>()
      val booksBySeriesPath = mutableMapOf<Path, MutableList<Book>>()

      collected.pathToBooks.forEach { (dir, scannedBooks) ->
        val group = groups[dir]
        val books = if (group == null) scannedBooks else scannedBooks.map { it.copy(volume = group.volumeName) }

        if (dir == root || (!oneshotsDir.isNullOrBlank() && dir.pathString.contains(oneshotsDir, true))) {
          books.forEach { book ->
            val series =
              Series(
                name = book.name,
                url = book.url,
                fileLastModified = book.fileLastModified,
                oneshot = true,
              )
            scannedSeries[series] = listOf(book.copy(oneshot = true))
          }
        } else {
          val seriesPath = group?.seriesPath ?: dir
          if (seriesByPath[seriesPath] == null) {
            val base =
              collected.pathToSeries[seriesPath]
                ?: Series(
                  name = SeriesTitleCleaner.clean(seriesPath.name) ?: seriesPath.name.ifBlank { seriesPath.pathString },
                  url = seriesPath.toUri().toURL(),
                  fileLastModified = LocalDateTime.MIN,
                )
            seriesByPath[seriesPath] = if (group == null) base else base.copy(name = group.seriesName)
          }
          booksBySeriesPath.getOrPut(seriesPath) { mutableListOf() }.addAll(books)
        }

        collected.pathToBookSidecars[dir]?.let { tempSidecars ->
          books.forEach { book ->
            val matched =
              tempSidecars
                .mapNotNull { sidecar ->
                  sidecarBookConsumers.firstOrNull { it.isSidecarBookMatch(book.name, sidecar.name) }?.let {
                    sidecar to it.getSidecarBookType()
                  }
                }
            tempSidecars.removeAll(matched.map { it.first }.toSet())
            matched.forEach { (sidecar, type) ->
              scannedSidecars.add(Sidecar(sidecar.url, book.url, sidecar.lastModifiedTime, type, Sidecar.Source.BOOK))
            }
          }
        }
      }

      booksBySeriesPath.forEach { (seriesPath, books) ->
        val base = seriesByPath.getValue(seriesPath)
        val lastModified =
          if (forceDirectoryModifiedTime) maxOf(base.fileLastModified, books.maxOf { it.fileLastModified })
          else base.fileLastModified
        scannedSeries[base.copy(fileLastModified = lastModified)] = books
        collected.pathToSeriesSidecars[seriesPath]?.let { scannedSidecars.addAll(it) }
      }
    }.also {
      val countOfBooks = scannedSeries.values.sumOf { it.size }
      logger.info { "Scanned ${scannedSeries.size} series, $countOfBooks books, and ${scannedSidecars.size} sidecars in $it" }
    }

    return ScanResult(scannedSeries, scannedSidecars)
  }

  fun previewVolumeGrouping(
    root: Path,
    scanCbx: Boolean = true,
    scanPdf: Boolean = true,
    scanEpub: Boolean = true,
    scanTxt: Boolean = true,
    directoryExclusions: Set<String> = emptySet(),
  ): VolumeGroupingPreview {
    if (!(Files.isDirectory(root) && Files.isReadable(root)))
      throw DirectoryNotFoundException("Folder is not accessible: $root", "ERR_1016")

    val collected = collect(root, extensionsToScan(scanCbx, scanPdf, scanEpub, scanTxt), directoryExclusions)
    val groups = resolveVolumeGroups(root, collected.pathToBooks.keys)
    val grouped = groups.entries.groupBy { it.value.seriesPath }

    val previews =
      grouped.map { (seriesPath, entries) ->
        VolumeGroupPreview(
          seriesName = entries.first().value.seriesName,
          path = seriesPath.pathString,
          volumes =
            entries
              .sortedBy { it.key.name }
              .map { VolumePreview(it.value.volumeName, collected.pathToBooks[it.key]?.size ?: 0) },
        )
      }.sortedBy { it.seriesName }

    return VolumeGroupingPreview(
      dirs = collected.pathToBooks.size,
      series = collected.pathToBooks.size - groups.size + grouped.size,
      groups = previews,
    )
  }

  private fun extensionsToScan(
    scanCbx: Boolean,
    scanPdf: Boolean,
    scanEpub: Boolean,
    scanTxt: Boolean,
  ): List<String> =
    buildList {
      if (scanCbx) addAll(listOf("cbz", "zip", "cbr", "rar"))
      if (scanPdf) add("pdf")
      if (scanEpub) {
        add("epub")
        add("mobi")
        add("azw3")
        add("fb2")
      }
      if (scanTxt) add("txt")
    }

  private fun collect(
    root: Path,
    scanForExtensions: List<String>,
    directoryExclusions: Set<String>,
  ): Collected {
    val pathToSeries = mutableMapOf<Path, Series>()
    val pathToSeriesSidecars = mutableMapOf<Path, MutableList<Sidecar>>()
    val pathToBooks = mutableMapOf<Path, MutableList<Book>>()
    val pathToBookSidecars = mutableMapOf<Path, MutableList<TempSidecar>>()

    Files.walkFileTree(
      root,
      setOf(FileVisitOption.FOLLOW_LINKS),
      Integer.MAX_VALUE,
      object : FileVisitor<Path> {
        override fun preVisitDirectory(
          dir: Path,
          attrs: BasicFileAttributes,
        ): FileVisitResult {
          logger.trace { "preVisit: $dir (regularFile:${attrs.isRegularFile}, directory:${attrs.isDirectory}, symbolicLink:${attrs.isSymbolicLink}, other:${attrs.isOther})" }
          if (dir.name.startsWith(".") ||
            directoryExclusions.any { exclude ->
              dir.pathString.contains(exclude, true)
            }
          )
            return FileVisitResult.SKIP_SUBTREE

          if (dir == root) return FileVisitResult.CONTINUE

          pathToSeries[dir] =
            Series(
              name = SeriesTitleCleaner.clean(dir.name) ?: dir.name.ifBlank { dir.pathString },
              url = dir.toUri().toURL(),
              fileLastModified = attrs.getUpdatedTime(),
            )

          return FileVisitResult.CONTINUE
        }

        override fun visitFile(
          file: Path,
          attrs: BasicFileAttributes,
        ): FileVisitResult {
          logger.trace { "visitFile: $file (regularFile:${attrs.isRegularFile}, directory:${attrs.isDirectory}, symbolicLink:${attrs.isSymbolicLink}, other:${attrs.isOther})" }
          if (!attrs.isSymbolicLink && !attrs.isDirectory) {
            if (scanForExtensions.contains(file.extension.lowercase()) &&
              !file.name.startsWith(".")
            ) {
              val book = pathToBook(file, attrs)
              file.parent.let { key ->
                pathToBooks.merge(key, mutableListOf(book)) { prev, one -> prev.union(one).toMutableList() }
              }
            }

            sidecarSeriesConsumers
              .firstOrNull { consumer ->
                consumer.getSidecarSeriesFilenames().any { file.name.equals(it, ignoreCase = true) }
              }?.let {
                val sidecar = Sidecar(file.toUri().toURL(), file.parent.toUri().toURL(), attrs.getUpdatedTime(), it.getSidecarSeriesType(), Sidecar.Source.SERIES)
                pathToSeriesSidecars.merge(file.parent, mutableListOf(sidecar)) { prev, one -> prev.union(one).toMutableList() }
              }

            if (sidecarBookPrefilter.any { it.matches(file.name) }) {
              val sidecar = TempSidecar(file.name, file.toUri().toURL(), attrs.getUpdatedTime())
              pathToBookSidecars.merge(file.parent, mutableListOf(sidecar)) { prev, one -> prev.union(one).toMutableList() }
            }
          }

          return FileVisitResult.CONTINUE
        }

        override fun visitFileFailed(
          file: Path?,
          exc: IOException?,
        ): FileVisitResult {
          logger.warn { "Could not access: $file" }
          return FileVisitResult.SKIP_SUBTREE
        }

        override fun postVisitDirectory(
          dir: Path,
          exc: IOException?,
        ): FileVisitResult {
          logger.trace { "postVisit: $dir" }
          return FileVisitResult.CONTINUE
        }
      },
    )

    return Collected(pathToSeries, pathToSeriesSidecars, pathToBooks, pathToBookSidecars)
  }

  private fun resolveVolumeGroups(
    root: Path,
    bookDirs: Collection<Path>,
  ): Map<Path, VolumeGroup> {
    val result = mutableMapOf<Path, VolumeGroup>()
    bookDirs
      .filter { it != root }
      .groupBy { it.parent }
      .forEach { (parent, children) ->
        parent ?: return@forEach
        if (parent == root || children.size < 2) return@forEach
        if (bookDirs.contains(parent)) return@forEach
        val names = children.map { it.name }
        val seriesName = resolveSeriesName(parent, names) ?: return@forEach
        children.forEach { child ->
          val volumeName = volumeNameOf(child.name, seriesName)
          if (volumeName.isNotBlank()) result[child] = VolumeGroup(parent, seriesName, volumeName)
        }
      }
    return result
  }

  private fun resolveSeriesName(
    parent: Path,
    names: List<String>,
  ): String? {
    val prefix = names.reduce { acc, name -> commonPrefix(acc, name) }
    val cut = prefix.indexOfLast { it in VOLUME_SEPARATORS }
    if (cut >= 2) {
      val candidate = prefix.substring(0, cut).trim()
      if (candidate.length >= 2 && names.all { it.length > candidate.length }) return candidate
    }

    val parentName = parent.name.trim()
    if (parentName.length < 2) return null
    if (names.all { name -> VOLUME_SEPARATORS.any { sep -> name.startsWith(parentName + sep) } }) return parentName
    if (names.all { VOLUME_NUMBER_PATTERN.matches(it.trim()) }) return parentName
    return null
  }

  private fun commonPrefix(
    a: String,
    b: String,
  ): String {
    val limit = minOf(a.length, b.length)
    var index = 0
    while (index < limit && a[index] == b[index]) index++
    return a.substring(0, index)
  }

  private fun volumeNameOf(
    dirName: String,
    seriesName: String,
  ): String {
    if (!dirName.startsWith(seriesName)) return dirName.trim()
    return dirName.substring(seriesName.length).trimStart(*VOLUME_SEPARATORS).trim()
  }

  fun scanFile(path: Path): Book? {
    if (!path.exists()) return null

    return pathToBook(path, path.readAttributes())
  }

  fun scanBookSidecars(path: Path): List<Sidecar> {
    val bookBaseName = path.nameWithoutExtension
    val parent = path.parent
    return parent
      .listDirectoryEntries()
      .filter { candidate -> sidecarBookPrefilter.any { it.matches(candidate.name) } }
      .mapNotNull { candidate ->
        sidecarBookConsumers.firstOrNull { it.isSidecarBookMatch(bookBaseName, candidate.name) }?.let {
          Sidecar(candidate.toUri().toURL(), parent.toUri().toURL(), candidate.readAttributes<BasicFileAttributes>().getUpdatedTime(), it.getSidecarBookType(), Sidecar.Source.BOOK)
        }
      }
  }

  private fun pathToBook(
    path: Path,
    attrs: BasicFileAttributes,
  ): Book =
    Book(
      name = SeriesTitleCleaner.clean(path.nameWithoutExtension) ?: path.nameWithoutExtension,
      url = path.toUri().toURL(),
      fileLastModified = attrs.getUpdatedTime(),
      fileSize = attrs.size(),
    )
}

fun BasicFileAttributes.getUpdatedTime(): LocalDateTime = maxOf(creationTime(), lastModifiedTime()).toLocalDateTime()

fun FileTime.toLocalDateTime(): LocalDateTime = LocalDateTime.ofInstant(this.toInstant(), ZoneId.systemDefault())
