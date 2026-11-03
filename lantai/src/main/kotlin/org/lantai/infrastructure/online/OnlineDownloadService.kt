package org.lantai.infrastructure.online

import io.github.oshai.kotlinlogging.KotlinLogging
import org.lantai.domain.model.Book
import org.lantai.domain.model.OnlineChapter
import org.lantai.domain.persistence.BookRepository
import org.lantai.domain.persistence.LibraryRepository
import org.lantai.domain.persistence.MediaRepository
import org.lantai.domain.persistence.OnlineChapterRepository
import org.lantai.domain.persistence.OnlineMangaRepository
import org.lantai.domain.persistence.SeriesRepository
import org.lantai.domain.service.BookAnalyzer
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

private val logger = KotlinLogging.logger {}

data class OnlineDownloadResult(
  val bookId: String,
  val pageCount: Int,
  val sizeBytes: Long,
  val alreadyDownloaded: Boolean,
)

@Service
class OnlineDownloadService(
  private val client: SuwayomiClient,
  private val properties: OnlineProperties,
  private val onlineChapterRepository: OnlineChapterRepository,
  private val onlineMangaRepository: OnlineMangaRepository,
  private val seriesRepository: SeriesRepository,
  private val libraryRepository: LibraryRepository,
  private val bookRepository: BookRepository,
  private val mediaRepository: MediaRepository,
  private val bookAnalyzer: BookAnalyzer,
) {
  private val locks = ConcurrentHashMap<String, Any>()

  fun download(bookId: String): OnlineDownloadResult =
    synchronized(locks.computeIfAbsent(bookId) { Any() }) {
      if (properties.suwayomiDownloadsDir.isBlank()) throw OnlineException("Suwayomi downloads dir is not configured")

      val chapter = onlineChapterRepository.findByBookIdOrNull(bookId) ?: throw OnlineException("Not an online book: $bookId")
      val book = bookRepository.findByIdOrNull(bookId) ?: throw OnlineException("Book not found: $bookId")
      val target = targetFile(book)

      if (target.exists() && Files.size(target) > 0) {
        return@synchronized persistDownload(book, chapter, target, true)
      }

      if (chapter.remoteId <= 0) throw OnlineException("Chapter ${chapter.title} has no remote id")

      val cached = locateCbz(chapter)
      if (cached == null) {
        client.enqueueChapterDownload(chapter.remoteId)
        waitUntilDownloaded(chapter.remoteId)
      }

      val source = cached ?: locateCbz(chapter) ?: throw OnlineException("Downloaded CBZ not found for chapter ${chapter.title}")
      Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
      persistDownload(book, chapter, target, false)
    }

  private fun persistDownload(
    book: Book,
    chapter: OnlineChapter,
    cbz: Path,
    alreadyDownloaded: Boolean,
  ): OnlineDownloadResult {
    val size = Files.size(cbz)
    val updated =
      book.copy(
        url = cbz.toUri().toURL(),
        fileSize = size,
        fileLastModified = Files.getLastModifiedTime(cbz).toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime(),
        lastModifiedDate = LocalDateTime.now(),
      )
    bookRepository.update(updated)

    val media = bookAnalyzer.analyze(updated, libraryRepository.findById(updated.libraryId).analyzeDimensions)
    mediaRepository.update(media)

    if (book.path != cbz) runCatching { Files.deleteIfExists(book.path) }
    onlineChapterRepository.update(chapter.copy(downloaded = true, pageCount = media.pageCount))

    logger.info { "Stored online book ${book.id} as CBZ: ${media.pageCount} pages, $size bytes" }
    return OnlineDownloadResult(book.id, media.pageCount, size, alreadyDownloaded)
  }

  private fun waitUntilDownloaded(chapterId: Long) {
    val deadline = System.currentTimeMillis() + properties.downloadTimeoutSeconds * 1000L
    while (System.currentTimeMillis() < deadline) {
      if (client.isChapterDownloaded(chapterId)) return
      Thread.sleep(properties.downloadPollMillis.coerceAtLeast(200))
    }
    throw OnlineException("Timed out waiting for Suwayomi to download chapter $chapterId")
  }

  private fun targetFile(book: Book): Path {
    val current = book.path
    if (current.fileName.toString().endsWith(".cbz", ignoreCase = true)) return current
    return current.resolveSibling(current.fileName.toString().removeSuffix(".placeholder") + ".cbz")
  }

  private fun locateCbz(chapter: OnlineChapter): Path? {
    val root = Paths.get(properties.suwayomiDownloadsDir)
    val mangas = root.resolve("mangas")
    if (!mangas.exists() || !mangas.isDirectory()) return null

    val manga = onlineMangaRepository.findByIdOrNull(chapter.mangaId) ?: return null
    val seriesName = seriesRepository.findByIdOrNull(manga.seriesId)?.name ?: return null
    val directories =
      runCatching {
        mangas
          .listDirectoryEntries()
          .filter { it.isDirectory() }
          .mapNotNull { source -> source.resolve(seriesName).takeIf { it.exists() && it.isDirectory() } }
      }.getOrDefault(emptyList())
    if (directories.isEmpty()) return null

    directories.forEach { dir ->
      val exact = dir.resolve("${chapter.title}.cbz")
      if (exact.exists()) return exact
    }
    directories.forEach { dir ->
      val byIndex =
        runCatching {
          dir.listDirectoryEntries().firstOrNull { entry ->
            val name = entry.fileName.toString()
            name.endsWith(".cbz", ignoreCase = true) && name.startsWith("${chapter.idx}.")
          }
        }.getOrNull()
      if (byIndex != null) return byIndex
    }
    return null
  }
}
