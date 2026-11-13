package org.lantai.infrastructure.metadata.scrape

import io.github.oshai.kotlinlogging.KotlinLogging
import org.lantai.domain.model.BookWithMedia
import org.lantai.domain.model.MetadataPatchTarget
import org.lantai.domain.model.Series
import org.lantai.domain.persistence.BookRepository
import org.lantai.domain.persistence.LibraryRepository
import org.lantai.domain.persistence.MediaRepository
import org.lantai.infrastructure.metadata.AuthorNameCleaner
import org.lantai.infrastructure.metadata.BookMetadataProvider
import org.lantai.infrastructure.metadata.SeriesMetadataFromBookProvider
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

@Service
class ScrapeEmbeddedReader(
  private val bookRepository: BookRepository,
  private val mediaRepository: MediaRepository,
  private val libraryRepository: LibraryRepository,
  private val seriesMetadataFromBookProviders: List<SeriesMetadataFromBookProvider>,
  private val bookMetadataProviders: List<BookMetadataProvider>,
) {
  private val cache = java.util.concurrent.ConcurrentHashMap<String, CacheEntry>()

  fun read(series: Series): EmbeddedRead {
    val now = System.currentTimeMillis()
    cache[series.id]?.takeIf { now - it.at < CACHE_TTL_MS }?.let { return it.read }
    val read = readFresh(series)
    cache[series.id] = CacheEntry(now, read)
    if (cache.size > MAX_CACHE) cache.clear()
    return read
  }

  private fun readFresh(series: Series): EmbeddedRead {
    val library =
      runCatching { libraryRepository.findById(series.libraryId) }.getOrNull()
        ?: return EmbeddedRead()
    val books = bookRepository.findAllBySeriesId(series.id)
    if (books.isEmpty()) return EmbeddedRead()
    val withMedia = books.mapNotNull { book -> runCatching { BookWithMedia(book, mediaRepository.findById(book.id)) }.getOrNull() }

    val seriesValues = readSeriesValues(series, withMedia, library)
    val bookValues =
      withMedia.mapNotNull { entry ->
        val values = readBookValues(entry, library)
        if (values.authors.isEmpty() && values.releaseDate == null && values.isbn.isBlank() && values.summary.isBlank()) {
          null
        } else {
          EmbeddedBook(entry.book.id, values)
        }
      }

    val seriesRegion =
      if (seriesValues.region.isNotBlank()) {
        seriesValues.region
      } else {
        bookValues.firstNotNullOfOrNull { it.values.region.takeIf { region -> region.isNotBlank() } }.orEmpty()
      }

    return EmbeddedRead(series = seriesValues.copy(region = seriesRegion), books = bookValues)
  }

  private fun readSeriesValues(
    series: Series,
    books: List<BookWithMedia>,
    library: org.lantai.domain.model.Library,
  ): ScrapeValues {
    var title: String? = null
    var summary: String? = null
    var publisher: String? = null
    var language: String? = null
    val genres = linkedSetOf<String>()

    seriesMetadataFromBookProviders.forEach { provider ->
      if (!provider.shouldLibraryHandlePatch(library, MetadataPatchTarget.SERIES)) return@forEach
      books.forEach { entry ->
        val patch =
          runCatching { provider.getSeriesMetadataFromBook(entry, library.importComicInfoSeriesAppendVolume) }
            .onFailure { logger.debug(it) { "Embedded series metadata failed for ${series.id}" } }
            .getOrNull()
            ?: return@forEach
        if (title.isNullOrBlank()) title = patch.title?.takeIf { it.isNotBlank() }
        if (summary.isNullOrBlank()) summary = patch.summary?.takeIf { it.isNotBlank() }
        if (publisher.isNullOrBlank()) publisher = patch.publisher?.takeIf { it.isNotBlank() }
        if (language.isNullOrBlank()) language = patch.language?.takeIf { it.isNotBlank() }
        patch.genres?.filter { it.isNotBlank() }?.forEach { genres += it }
      }
    }

    return ScrapeValues(
      title = title.orEmpty(),
      summary = summary.orEmpty(),
      publisher = publisher.orEmpty(),
      language = language.orEmpty(),
      genres = genres.toList(),
    )
  }

  private fun readBookValues(
    entry: BookWithMedia,
    library: org.lantai.domain.model.Library,
  ): ScrapeValues {
    var summary = ""
    var releaseDate: java.time.LocalDate? = null
    var isbn = ""
    val authors = linkedSetOf<String>()
    val tags = linkedSetOf<String>()
    var region = ""

    bookMetadataProviders.forEach { provider ->
      if (!provider.shouldLibraryHandlePatch(library, MetadataPatchTarget.BOOK)) return@forEach
      val patch =
        runCatching { provider.getBookMetadataFromBook(entry) }
          .onFailure { logger.debug(it) { "Embedded book metadata failed for ${entry.book.id}" } }
          .getOrNull()
          ?: return@forEach
      if (summary.isBlank()) summary = patch.summary?.takeIf { it.isNotBlank() }.orEmpty()
      if (releaseDate == null) releaseDate = patch.releaseDate
      if (isbn.isBlank()) isbn = patch.isbn?.takeIf { it.isNotBlank() }.orEmpty()
      patch.authors?.filter { it.name.isNotBlank() }?.forEach {
        if (region.isBlank()) region = AuthorNameCleaner.regionOf(it.name)
        val name = AuthorNameCleaner.clean(it.name)
        if (name.isNotBlank()) authors += name
      }
      patch.tags?.filter { it.isNotBlank() }?.forEach { tags += it }
    }

    return ScrapeValues(
      summary = summary,
      releaseDate = releaseDate,
      isbn = isbn,
      region = region,
      authors = authors.toList(),
      tags = tags.toList(),
    )
  }

  private data class CacheEntry(
    val at: Long,
    val read: EmbeddedRead,
  )

  companion object {
    private const val CACHE_TTL_MS = 60_000L
    private const val MAX_CACHE = 2_000
  }
}
