package org.lantai.domain.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.lantai.domain.model.Book
import org.lantai.domain.model.Library
import org.lantai.domain.model.Series
import org.lantai.domain.model.TrashEntry
import org.lantai.domain.persistence.BookRepository
import org.lantai.domain.persistence.SeriesRepository
import org.lantai.domain.persistence.TrashEntryRepository
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.util.Comparator
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

private val logger = KotlinLogging.logger {}

@Service
class TrashBin(
  private val trashEntryRepository: TrashEntryRepository,
  private val seriesRepository: SeriesRepository,
  private val bookRepository: BookRepository,
  private val seriesLifecycle: SeriesLifecycle,
  private val bookLifecycle: BookLifecycle,
) {
  companion object {
    const val TRASH_DIR = ".lantai-trash"
  }

  fun isEnabled(library: Library, globalMaxBytes: Long): Boolean = effectiveMaxBytes(library, globalMaxBytes) > 0

  fun effectiveMaxBytes(
    library: Library,
    globalMaxBytes: Long,
  ): Long = library.trashMaxBytes ?: globalMaxBytes

  fun moveInSeries(
    series: Series,
    library: Library,
    globalMaxBytes: Long,
  ): TrashEntry? {
    val source = series.path
    if (!source.exists()) {
      logger.info { "Skip trash, series path missing: $source" }
      return null
    }
    val draft = TrashEntry(
      itemType = TrashEntry.ItemType.SERIES,
      libraryId = library.id,
      seriesId = series.id,
      originalPath = source.toString(),
      trashedPath = "",
      sizeBytes = measure(source),
    )
    val target = relocate(source, library, draft.id)
    enforceQuota(library, globalMaxBytes, draft.sizeBytes)
    val entry = save(draft.copy(trashedPath = target.toString()))
    seriesLifecycle.softDeleteMany(listOf(series))
    logger.info { "Moved series ${series.id} to trash: $target (${entry.sizeBytes} bytes)" }
    return entry
  }

  fun moveInBook(
    book: Book,
    library: Library,
    globalMaxBytes: Long,
  ): TrashEntry? {
    val source = book.path
    if (!source.exists()) {
      logger.info { "Skip trash, book path missing: $source" }
      return null
    }
    val draft = TrashEntry(
      itemType = TrashEntry.ItemType.BOOK,
      libraryId = library.id,
      seriesId = book.seriesId,
      bookId = book.id,
      originalPath = source.toString(),
      trashedPath = "",
      sizeBytes = Files.size(source),
    )
    val target = relocate(source, library, draft.id)
    enforceQuota(library, globalMaxBytes, draft.sizeBytes)
    val entry = save(draft.copy(trashedPath = target.toString()))
    bookLifecycle.softDeleteMany(listOf(book))
    logger.info { "Moved book ${book.id} to trash: $target (${entry.sizeBytes} bytes)" }
    return entry
  }

  fun restore(entry: TrashEntry) {
    val source = Paths.get(entry.trashedPath)
    val target = Paths.get(entry.originalPath)
    if (!source.exists()) throw IllegalStateException("Trashed content is missing: $source")
    target.parent?.let { Files.createDirectories(it) }
    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
    removeEmptyTrashParent(source)
    trashEntryRepository.delete(entry.id)
    when (entry.itemType) {
      TrashEntry.ItemType.SERIES ->
        entry.seriesId?.let { id -> seriesRepository.findByIdOrNull(id) }?.let {
          seriesRepository.update(it.copy(deletedDate = null))
        }
      TrashEntry.ItemType.BOOK ->
        entry.bookId?.let { id -> bookRepository.findByIdOrNull(id) }?.let {
          bookRepository.update(it.copy(deletedDate = null))
        }
    }
    logger.info { "Restored ${entry.itemType} from trash: $target" }
  }

  fun purge(entry: TrashEntry) {
    val source = Paths.get(entry.trashedPath)
    deleteRecursively(source)
    removeEmptyTrashParent(source)
    trashEntryRepository.delete(entry.id)
    when (entry.itemType) {
      TrashEntry.ItemType.SERIES ->
        entry.seriesId?.let { id -> seriesRepository.findByIdOrNull(id) }?.let { seriesLifecycle.deleteMany(listOf(it)) }
      TrashEntry.ItemType.BOOK ->
        entry.bookId?.let { id -> bookRepository.findByIdOrNull(id) }?.let { bookLifecycle.deleteMany(listOf(it)) }
    }
    logger.info { "Purged ${entry.itemType} from trash: $source" }
  }

  fun purgeAll(libraryId: String) {
    trashEntryRepository.findAllByLibraryId(libraryId).forEach { purge(it) }
  }

  fun purgeExpired(retentionHours: Int) {
    if (retentionHours <= 0) return
    val cutoff = LocalDateTime.now().minusHours(retentionHours.toLong())
    val expired = trashEntryRepository.findDeletedBefore(cutoff)
    if (expired.isEmpty()) return
    logger.info { "Purging ${expired.size} expired trash entries older than $retentionHours hours" }
    expired.forEach { purge(it) }
  }

  fun enforceQuota(
    library: Library,
    globalMaxBytes: Long,
    incomingBytes: Long = 0,
  ) {
    val max = effectiveMaxBytes(library, globalMaxBytes)
    if (max <= 0) return
    var total = trashEntryRepository.totalSizeByLibraryId(library.id)
    while (total + incomingBytes > max) {
      val oldest = trashEntryRepository.findOldestByLibraryId(library.id, 1).firstOrNull() ?: break
      logger.info { "Trash quota exceeded for library ${library.id}, purging oldest ${oldest.id}" }
      purge(oldest)
      total -= oldest.sizeBytes
    }
  }

  private fun save(entry: TrashEntry): TrashEntry {
    trashEntryRepository.insert(entry)
    return entry
  }

  private fun relocate(
    source: Path,
    library: Library,
    entryId: String,
  ): Path {
    val root = library.path.resolve(TRASH_DIR)
    assertSameVolume(source, root)
    val targetDir = root.resolve(entryId)
    Files.createDirectories(targetDir)
    val target = targetDir.resolve(source.fileName.toString())
    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
    return target
  }

  private fun assertSameVolume(
    source: Path,
    target: Path,
  ) {
    val sourceRoot = source.toAbsolutePath().normalize().root
    val targetRoot = target.toAbsolutePath().normalize().root
    if (sourceRoot == null || targetRoot == null || sourceRoot != targetRoot) {
      throw IllegalStateException("Trash requires the same volume as the library: $sourceRoot vs $targetRoot")
    }
  }

  private fun measure(path: Path): Long =
    if (path.isDirectory()) {
      Files.walk(path).use { stream ->
        stream.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum()
      }
    } else {
      Files.size(path)
    }

  private fun deleteRecursively(path: Path) {
    if (!path.exists()) return
    if (!path.isDirectory()) {
      Files.deleteIfExists(path)
      return
    }
    Files.walk(path).use { stream ->
      stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }
  }

  private fun removeEmptyTrashParent(path: Path) {
    val parent = path.parent ?: return
    if (!parent.exists() || !parent.isDirectory()) return
    val empty = parent.listDirectoryEntries().isEmpty()
    if (empty) Files.deleteIfExists(parent)
  }
}
