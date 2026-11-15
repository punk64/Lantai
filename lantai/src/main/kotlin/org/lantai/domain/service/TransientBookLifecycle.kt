package org.lantai.domain.service

import org.lantai.domain.model.BookMetadataPatchCapability
import org.lantai.domain.model.Media
import org.lantai.domain.model.MediaNotReadyException
import org.lantai.domain.model.MediaProfile
import org.lantai.domain.model.PathContainedInPath
import org.lantai.domain.model.SearchCondition
import org.lantai.domain.model.SearchContext
import org.lantai.domain.model.SearchOperator
import org.lantai.domain.model.TransientBook
import org.lantai.domain.model.TypedBytes
import org.lantai.domain.model.toBookWithMedia
import org.lantai.domain.persistence.LibraryRepository
import org.lantai.domain.persistence.SeriesRepository
import org.lantai.domain.persistence.TransientBookRepository
import org.lantai.infrastructure.image.ImageType
import org.lantai.infrastructure.metadata.BookMetadataProvider
import org.lantai.infrastructure.metadata.SeriesMetadataFromBookProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import java.nio.file.Paths

@Service
class TransientBookLifecycle(
  private val transientBookRepository: TransientBookRepository,
  private val bookAnalyzer: BookAnalyzer,
  private val fileSystemScanner: FileSystemScanner,
  private val libraryRepository: LibraryRepository,
  @Qualifier("pdfImageType")
  private val pdfImageType: ImageType,
  private val seriesRepository: SeriesRepository,
  private val seriesMetadataProviders: List<SeriesMetadataFromBookProvider>,
  bookMetadataProviders: List<BookMetadataProvider>,
) {
  val bookMetadataProviders = bookMetadataProviders.filter { it.capabilities.contains(BookMetadataPatchCapability.NUMBER_SORT) }

  fun scanAndPersist(filePath: String): List<TransientBook> {
    val folderToScan = Paths.get(filePath)

    libraryRepository.findAll().forEach { library ->
      if (folderToScan.startsWith(library.path)) throw PathContainedInPath("Cannot scan folder that is part of an existing library", "ERR_1017")
    }

    val books =
      fileSystemScanner
        .scanRootFolder(folderToScan)
        .series.values
        .flatten()
        .map { TransientBook(it, Media()) }

    transientBookRepository.save(books)

    return books
  }

  fun analyzeAndPersist(transientBook: TransientBook): TransientBook {
    val media = bookAnalyzer.analyze(transientBook.book, true)
    val (seriesId, number) = getMetadata(transientBook.copy(media = media))

    val updated = transientBook.copy(media = media, metadata = TransientBook.Metadata(number, seriesId))
    transientBookRepository.save(updated)

    return updated
  }

  fun getMetadata(transientBook: TransientBook): Pair<String?, Float?> {
    val bookWithMedia = transientBook.toBookWithMedia()
    val number = bookMetadataProviders.firstNotNullOfOrNull { it.getBookMetadataFromBook(bookWithMedia)?.numberSort }
    val seriesNamesFromMetadata =
      seriesMetadataProviders
        .flatMap {
          buildList {
            if (it.supportsAppendVolume) add(it.getSeriesMetadataFromBook(bookWithMedia, true)?.title?.ifBlank { null })
            add(it.getSeriesMetadataFromBook(bookWithMedia, false)?.title?.ifBlank { null })
          }
        }.filterNotNull()

    val series =
      if (seriesNamesFromMetadata.isNotEmpty()) {
        val exactSearch = SearchCondition.AnyOfSeries(seriesNamesFromMetadata.map { SearchCondition.Title(SearchOperator.Is(it)) })
        val exactMatches = seriesRepository.findAll(exactSearch, SearchContext.ofAnonymousUser(), Pageable.unpaged())
        if (!exactMatches.isEmpty) {
          exactMatches.content.first()
        } else {
          val containsSearch = SearchCondition.AnyOfSeries(seriesNamesFromMetadata.map { SearchCondition.Title(SearchOperator.Contains(it)) })
          seriesRepository.findAll(containsSearch, SearchContext.ofAnonymousUser(), Pageable.unpaged()).content.firstOrNull()
        }
      } else {
        null
      }

    return series?.id to number
  }

  @Throws(
    MediaNotReadyException::class,
    IndexOutOfBoundsException::class,
  )
  fun getBookPage(
    transientBook: TransientBook,
    number: Int,
  ): TypedBytes {
    val pageContent = bookAnalyzer.getPageContent(transientBook.toBookWithMedia(), number)
    val pageMediaType =
      if (transientBook.media.profile == MediaProfile.PDF)
        pdfImageType.mediaType
      else
        transientBook.media.pages[number - 1].mediaType

    return TypedBytes(pageContent, pageMediaType)
  }
}
