package org.lantai.domain.service

import org.lantai.domain.model.Library
import org.lantai.domain.persistence.BookRepository
import org.lantai.domain.persistence.LibraryRepository
import org.lantai.domain.persistence.OnlineMangaRepository
import org.lantai.domain.persistence.SeriesRepository
import org.lantai.infrastructure.online.OnlineAvailability
import org.springframework.stereotype.Service

@Service
class OnlineContentGuard(
  private val libraryRepository: LibraryRepository,
  private val seriesRepository: SeriesRepository,
  private val bookRepository: BookRepository,
  private val onlineMangaRepository: OnlineMangaRepository,
) {
  fun isOnlineLibrary(libraryId: String): Boolean = libraryRepository.findByIdOrNull(libraryId)?.type == Library.Type.ONLINE

  fun isOnlineSeries(seriesId: String): Boolean =
    seriesRepository.findByIdOrNull(seriesId)?.let { isOnlineLibrary(it.libraryId) } ?: false

  fun isOnlineBook(bookId: String): Boolean =
    bookRepository.findByIdOrNull(bookId)?.let { isOnlineLibrary(it.libraryId) } ?: false

  fun isHiddenSeries(seriesId: String): Boolean {
    val hidden = OnlineAvailability.hiddenKinds()
    if (hidden.isEmpty()) return false
    val series = seriesRepository.findByIdOrNull(seriesId) ?: return false
    if (!isOnlineLibrary(series.libraryId)) return false
    val manga = onlineMangaRepository.findBySeriesIdOrNull(seriesId) ?: return false
    return manga.sourceKind in hidden
  }

  fun isHiddenBook(bookId: String): Boolean {
    if (OnlineAvailability.hiddenKinds().isEmpty()) return false
    val book = bookRepository.findByIdOrNull(bookId) ?: return false
    return isHiddenSeries(book.seriesId)
  }
}
