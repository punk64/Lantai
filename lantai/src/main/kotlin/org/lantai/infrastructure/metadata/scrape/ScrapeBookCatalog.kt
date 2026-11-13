package org.lantai.infrastructure.metadata.scrape

import org.lantai.domain.persistence.BookMetadataRepository
import org.lantai.domain.persistence.BookRepository
import org.springframework.stereotype.Service

@Service
class ScrapeBookCatalog(
  private val bookRepository: BookRepository,
  private val bookMetadataRepository: BookMetadataRepository,
) {
  fun list(seriesId: String): List<ScrapeBookDto> {
    val books = bookRepository.findAllBySeriesId(seriesId)
    if (books.isEmpty()) return emptyList()
    val ready = bookRepository.findReadinessBySeries(listOf(seriesId))[seriesId]?.readyBookIds.orEmpty().toSet()
    return books
      .filter { it.id in ready }
      .sortedBy { it.number }
      .map { book ->
        val metadata = runCatching { bookMetadataRepository.findByIdOrNull(book.id) }.getOrNull()
        ScrapeBookDto(
          bookId = book.id,
          fileName = book.name,
          title = metadata?.title?.takeIf { it.isNotBlank() } ?: book.name.substringBeforeLast('.', book.name),
          number = metadata?.number.orEmpty(),
          authors = metadata?.authors?.map { it.name }.orEmpty(),
          releaseDate = metadata?.releaseDate?.toString(),
          isbn = metadata?.isbn.orEmpty(),
          tags = metadata?.tags?.sorted().orEmpty(),
          summary = metadata?.summary.orEmpty(),
          publisher = metadata?.publisher.orEmpty(),
          genres = metadata?.genres?.sorted().orEmpty(),
          language = metadata?.language.orEmpty(),
          titleLock = metadata?.titleLock ?: false,
          numberLock = metadata?.numberLock ?: false,
          authorsLock = metadata?.authorsLock ?: false,
        )
      }
  }
}
