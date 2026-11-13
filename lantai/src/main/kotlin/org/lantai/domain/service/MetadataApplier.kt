package org.lantai.domain.service

import org.lantai.domain.model.BookMetadata
import org.lantai.domain.model.BookMetadataPatch
import org.lantai.domain.model.SeriesMetadata
import org.lantai.domain.model.SeriesMetadataPatch
import org.springframework.stereotype.Service

@Service
class MetadataApplier {
  private fun <T> getIfNotLocked(
    original: T,
    patched: T?,
    lock: Boolean,
  ): T =
    if (patched != null && !lock)
      patched
    else
      original

  fun apply(
    patch: BookMetadataPatch,
    metadata: BookMetadata,
  ): BookMetadata =
    with(metadata) {
      copy(
        title = getIfNotLocked(title, patch.title, titleLock),
        summary = getIfNotLocked(summary, patch.summary, summaryLock),
        number = getIfNotLocked(number, patch.number, numberLock),
        numberSort = getIfNotLocked(numberSort, patch.numberSort, numberSortLock),
        releaseDate = getIfNotLocked(releaseDate, patch.releaseDate, releaseDateLock),
        authors = getIfNotLocked(authors, patch.authors, authorsLock),
        notableEntities = getIfNotLocked(notableEntities, patch.notableEntities, notableEntitiesLock),
        isbn = getIfNotLocked(isbn, patch.isbn, isbnLock),
        links = getIfNotLocked(links, patch.links, linksLock),
        tags = getIfNotLocked(tags, patch.tags, tagsLock),
      )
    }

  fun apply(
    patch: SeriesMetadataPatch,
    metadata: SeriesMetadata,
  ): SeriesMetadata =
    with(metadata) {
      copy(
        status = getIfNotLocked(status, patch.status, statusLock),
        title = getIfNotLocked(title, patch.title, titleLock),
        titleSort = getIfNotLocked(titleSort, patch.titleSort, titleSortLock),
        summary = getIfNotLocked(summary, patch.summary, summaryLock),
        readingDirection = getIfNotLocked(readingDirection, patch.readingDirection, readingDirectionLock),
        ageRating = getIfNotLocked(ageRating, patch.ageRating, ageRatingLock),
        publisher = getIfNotLocked(publisher, patch.publisher, publisherLock),
        region = getIfNotLocked(region, patch.region, regionLock),
        language = getIfNotLocked(language, patch.language, languageLock),
        genres = getIfNotLocked(genres, patch.genres, genresLock),
        totalBookCount = getIfNotLocked(totalBookCount, patch.totalBookCount, totalBookCountLock),
      )
    }
}
