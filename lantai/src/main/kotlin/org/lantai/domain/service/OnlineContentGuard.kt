package org.lantai.domain.service

import org.lantai.domain.model.Library
import org.lantai.domain.persistence.BookRepository
import org.lantai.domain.persistence.LibraryRepository
import org.lantai.domain.persistence.SeriesRepository
import org.springframework.stereotype.Service

@Service
class OnlineContentGuard(
  private val libraryRepository: LibraryRepository,
  private val seriesRepository: SeriesRepository,
  private val bookRepository: BookRepository,
) {
  fun isOnlineLibrary(libraryId: String): Boolean = libraryRepository.findByIdOrNull(libraryId)?.type == Library.Type.ONLINE

  fun isOnlineSeries(seriesId: String): Boolean =
    seriesRepository.findByIdOrNull(seriesId)?.let { isOnlineLibrary(it.libraryId) } ?: false

  fun isOnlineBook(bookId: String): Boolean =
    bookRepository.findByIdOrNull(bookId)?.let { isOnlineLibrary(it.libraryId) } ?: false
}
