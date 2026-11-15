package org.lantai.infrastructure.online

import io.github.oshai.kotlinlogging.KotlinLogging
import org.lantai.domain.model.Book
import org.lantai.domain.model.OnlineChapter
import org.lantai.domain.model.OnlineContentKinds
import org.lantai.domain.persistence.BookRepository
import org.lantai.domain.persistence.LibraryRepository
import org.lantai.domain.persistence.MediaRepository
import org.lantai.domain.persistence.OnlineChapterRepository
import org.lantai.domain.persistence.OnlineMangaRepository
import org.lantai.domain.service.BookAnalyzer
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists

private val logger = KotlinLogging.logger {}

data class OnlineDownloadResult(
  val bookId: String,
  val pageCount: Int,
  val sizeBytes: Long,
  val alreadyDownloaded: Boolean,
)

@Service
class OnlineDownloadService(
  private val clients: OnlineRemoteClients,
  private val onlineChapterRepository: OnlineChapterRepository,
  private val onlineMangaRepository: OnlineMangaRepository,
  private val bookRepository: BookRepository,
  private val mediaRepository: MediaRepository,
  private val libraryRepository: LibraryRepository,
  private val bookAnalyzer: BookAnalyzer,
) {
  private val locks = ConcurrentHashMap<String, Any>()

  fun download(bookId: String): OnlineDownloadResult =
    synchronized(locks.computeIfAbsent(bookId) { Any() }) {
      val chapter = onlineChapterRepository.findByBookIdOrNull(bookId) ?: throw OnlineException("Not an online book: $bookId")
      val manga = onlineMangaRepository.findByIdOrNull(chapter.mangaId) ?: throw OnlineException("Online manga not found for book $bookId")
      val kind = OnlineContentKinds.resolve(chapter.contentKind, manga.contentKind)
      if (OnlineContentKinds.isTextual(kind)) throw OnlineException("文字源与图文源不支持整章下载：${chapter.title}")
      val book = bookRepository.findByIdOrNull(bookId) ?: throw OnlineException("Book not found: $bookId")
      val target = targetFile(book)

      if (target.exists() && Files.size(target) > 0) {
        return@synchronized persistDownload(book, chapter, target, true)
      }

      if (chapter.remoteId <= 0) throw OnlineException("Chapter ${chapter.title} has no remote id")

      val bytes =
        clients
          .of(manga.sourceKind)
          .download(manga.remoteMangaId.toLong(), chapter.remoteChapterIndex)
          ?: throw OnlineException("源未返回章节文件：${chapter.title}")
      if (bytes.isEmpty()) throw OnlineException("源返回空文件：${chapter.title}")

      Files.createDirectories(target.parent)
      Files.write(target, bytes)
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

  private fun targetFile(book: Book): Path {
    val current = book.path
    if (current.fileName.toString().endsWith(".cbz", ignoreCase = true)) return current
    return current.resolveSibling(current.fileName.toString().removeSuffix(".placeholder") + ".cbz")
  }
}
