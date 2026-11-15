package org.lantai.domain.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.lantai.domain.model.BookPageNumbered
import org.lantai.domain.model.Library
import org.lantai.domain.model.MediaType
import org.lantai.domain.model.PageHashKnown
import org.lantai.domain.model.TypedBytes
import org.lantai.domain.persistence.BookRepository
import org.lantai.domain.persistence.MediaRepository
import org.lantai.domain.persistence.PageHashRepository
import org.lantai.infrastructure.configuration.LantaiProperties
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

@Service
class PageHashLifecycle(
  private val pageHashRepository: PageHashRepository,
  private val mediaRepository: MediaRepository,
  private val bookLifecycle: BookLifecycle,
  private val bookRepository: BookRepository,
  private val lantaiProperties: LantaiProperties,
) {
  private val hashableMediaTypes = listOf(MediaType.ZIP.type)

  fun getBookIdsWithMissingPageHash(library: Library): Collection<String> =
    if (library.hashPages) {
      mediaRepository
        .findAllBookIdsByLibraryIdAndMediaTypeAndWithMissingPageHash(library.id, hashableMediaTypes, lantaiProperties.pageHashing)
        .also { logger.info { "Found ${it.size} books with missing page hash" } }
    } else {
      logger.info { "Page hashing is not enabled, skipping" }
      emptyList()
    }

  fun getPage(
    pageHash: String,
    resizeTo: Int? = null,
  ): TypedBytes? {
    val match = pageHashRepository.findMatchesByHash(pageHash, Pageable.ofSize(1)).firstOrNull() ?: return null
    val book = bookRepository.findByIdOrNull(match.bookId) ?: return null

    return bookLifecycle.getBookPage(book, match.pageNumber, resizeTo = resizeTo)
  }

  fun getBookPagesToDeleteAutomatically(library: Library): Map<String, Collection<BookPageNumbered>> = pageHashRepository.findMatchesByKnownHashAction(listOf(PageHashKnown.Action.DELETE_AUTO), library.id)

  fun createOrUpdate(pageHash: PageHashKnown) {
    val existing = pageHashRepository.findKnown(pageHash.hash)
    if (existing == null) {
      pageHashRepository.insert(pageHash, getPage(pageHash.hash, 500)?.bytes)
    } else {
      pageHashRepository.update(existing.copy(action = pageHash.action))
    }
  }
}
