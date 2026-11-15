package org.lantai.interfaces.api

import org.lantai.domain.model.Book
import org.lantai.domain.model.LantaiUser
import org.lantai.domain.persistence.BookRepository
import org.lantai.domain.persistence.SeriesMetadataRepository
import org.lantai.domain.persistence.SeriesRepository
import org.lantai.domain.persistence.ThumbnailBookRepository
import org.lantai.domain.persistence.ThumbnailSeriesRepository
import org.lantai.interfaces.api.rest.dto.BookDto
import org.lantai.interfaces.api.rest.dto.SeriesDto
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException

@Component
class ContentRestrictionChecker(
  private val seriesMetadataRepository: SeriesMetadataRepository,
  private val bookRepository: BookRepository,
  private val thumbnailBookRepository: ThumbnailBookRepository,
  private val seriesRepository: SeriesRepository,
  private val thumbnailSeriesRepository: ThumbnailSeriesRepository,
) {
  /**
   * Convenience function to check for content restriction.
   * This will retrieve data from repositories if needed.
   *
   * @throws[ResponseStatusException] if the user cannot access the content
   */
  fun checkContentRestrictionBook(
    lantaiUser: LantaiUser,
    book: BookDto,
  ) {
    if (!lantaiUser.canAccessLibrary(book.libraryId)) throw ResponseStatusException(HttpStatus.FORBIDDEN)
    if (lantaiUser.restrictions.isRestricted)
      seriesMetadataRepository.findById(book.seriesId).let {
        if (!lantaiUser.isContentAllowed(it.ageRating, it.sharingLabels)) throw ResponseStatusException(HttpStatus.FORBIDDEN)
      }
  }

  /**
   * Convenience function to check for content restriction.
   * This will retrieve data from repositories if needed.
   *
   * @throws[ResponseStatusException] if the user cannot access the content
   */
  fun checkContentRestrictionBook(
    lantaiUser: LantaiUser,
    book: Book,
  ) {
    if (!lantaiUser.canAccessLibrary(book.libraryId)) throw ResponseStatusException(HttpStatus.FORBIDDEN)
    if (lantaiUser.restrictions.isRestricted)
      seriesMetadataRepository.findById(book.seriesId).let {
        if (!lantaiUser.isContentAllowed(it.ageRating, it.sharingLabels)) throw ResponseStatusException(HttpStatus.FORBIDDEN)
      }
  }

  /**
   * Convenience function to check for content restriction.
   * This will retrieve data from repositories if needed.
   *
   * @throws[ResponseStatusException] if the user cannot access the content
   */
  fun checkContentRestrictionBook(
    lantaiUser: LantaiUser,
    bookId: String,
  ) {
    if (!lantaiUser.canAccessAllLibraries()) {
      bookRepository.getLibraryIdOrNull(bookId)?.let {
        if (!lantaiUser.canAccessLibrary(it)) throw ResponseStatusException(HttpStatus.FORBIDDEN)
      } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    }
    if (lantaiUser.restrictions.isRestricted)
      bookRepository.getSeriesIdOrNull(bookId)?.let { seriesId ->
        seriesMetadataRepository.findById(seriesId).let {
          if (!lantaiUser.isContentAllowed(it.ageRating, it.sharingLabels)) throw ResponseStatusException(HttpStatus.FORBIDDEN)
        }
      } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
  }

  /**
   * Convenience function to check for content restriction.
   *
   * @throws[ResponseStatusException] if the user cannot access the content
   */
  fun checkContentRestrictionBookThumbnail(
    lantaiUser: LantaiUser,
    thumbnailId: String,
  ) {
    if (!lantaiUser.canAccessAllLibraries()) {
      thumbnailBookRepository.getLibraryIdOrNull(thumbnailId)?.let {
        if (!lantaiUser.canAccessLibrary(it)) throw ResponseStatusException(HttpStatus.FORBIDDEN)
      } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    }
    if (lantaiUser.restrictions.isRestricted)
      thumbnailBookRepository.getSeriesIdOrNull(thumbnailId)?.let { seriesId ->
        seriesMetadataRepository.findById(seriesId).let {
          if (!lantaiUser.isContentAllowed(it.ageRating, it.sharingLabels)) throw ResponseStatusException(HttpStatus.FORBIDDEN)
        }
      } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
  }

  /**
   * Convenience function to check for content restriction.
   *
   * @throws[ResponseStatusException] if the user cannot access the content
   */
  fun checkContentRestrictionSeries(
    lantaiUser: LantaiUser,
    series: SeriesDto,
  ) {
    if (!lantaiUser.canAccessLibrary(series.libraryId)) throw ResponseStatusException(HttpStatus.FORBIDDEN)
    if (!lantaiUser.isContentAllowed(series.metadata.ageRating, series.metadata.sharingLabels)) throw ResponseStatusException(HttpStatus.FORBIDDEN)
  }

  /**
   * Convenience function to check for content restriction.
   *
   * @throws[ResponseStatusException] if the user cannot access the content
   */
  fun checkContentRestrictionSeries(
    lantaiUser: LantaiUser,
    seriesId: String,
  ) {
    if (!lantaiUser.canAccessAllLibraries()) {
      seriesRepository.getLibraryId(seriesId)?.let {
        if (!lantaiUser.canAccessLibrary(it)) throw ResponseStatusException(HttpStatus.FORBIDDEN)
      } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    }
    if (lantaiUser.restrictions.isRestricted)
      seriesMetadataRepository.findById(seriesId).let {
        if (!lantaiUser.isContentAllowed(it.ageRating, it.sharingLabels)) throw ResponseStatusException(HttpStatus.FORBIDDEN)
      }
  }

  /**
   * Convenience function to check for content restriction.
   *
   * @throws[ResponseStatusException] if the user cannot access the content
   */
  fun checkContentRestrictionSeriesThumbnail(
    lantaiUser: LantaiUser,
    thumbnailId: String,
  ) {
    if (!lantaiUser.canAccessAllLibraries()) {
      thumbnailSeriesRepository.getLibraryIdOrNull(thumbnailId)?.let {
        if (!lantaiUser.canAccessLibrary(it)) throw ResponseStatusException(HttpStatus.FORBIDDEN)
      } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    }
    if (lantaiUser.restrictions.isRestricted)
      thumbnailSeriesRepository.getSeriesIdOrNull(thumbnailId)?.let { seriesId ->
        seriesMetadataRepository.findById(seriesId).let {
          if (!lantaiUser.isContentAllowed(it.ageRating, it.sharingLabels)) throw ResponseStatusException(HttpStatus.FORBIDDEN)
        }
      } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
  }
}
