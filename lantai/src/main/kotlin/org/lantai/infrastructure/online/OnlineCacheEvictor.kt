package org.lantai.infrastructure.online

import io.github.oshai.kotlinlogging.KotlinLogging
import org.lantai.domain.model.Book
import org.lantai.domain.persistence.BookRepository
import org.lantai.domain.persistence.OnlineChapterRepository
import org.lantai.domain.persistence.SeriesRepository
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.time.LocalDateTime
import kotlin.io.path.exists

private val logger = KotlinLogging.logger {}

@Service
class OnlineCacheEvictor(
  private val onlineChapterRepository: OnlineChapterRepository,
  private val bookRepository: BookRepository,
  private val seriesRepository: SeriesRepository,
  private val onlineLibraryService: OnlineLibraryService,
) {
  fun evictSeries(seriesId: String): Int = bookRepository.findAllBySeriesId(seriesId).count { evict(it) }

  fun evictBook(bookId: String): Boolean = bookRepository.findByIdOrNull(bookId)?.let { evict(it) } ?: false

  private fun evict(book: Book): Boolean {
    val chapter = onlineChapterRepository.findByBookIdOrNull(book.id) ?: return false
    if (!chapter.downloaded) return false

    val cached = book.path
    if (cached.exists() && cached.fileName.toString().endsWith(".cbz", ignoreCase = true)) {
      Files.deleteIfExists(cached)
      logger.info { "Evicted online cache file: $cached" }
    }

    val series = seriesRepository.findByIdOrNull(book.seriesId)
    if (series != null) {
      val placeholder = onlineLibraryService.chapterPlaceholder(series.path, chapter.title)
      bookRepository.update(
        book.copy(
          url = placeholder.toUri().toURL(),
          fileSize = 0,
          fileLastModified = LocalDateTime.now(),
        ),
      )
    }

    onlineChapterRepository.update(chapter.copy(downloaded = false))
    return true
  }
}
