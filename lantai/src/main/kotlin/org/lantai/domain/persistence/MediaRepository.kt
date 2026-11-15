package org.lantai.domain.persistence

import org.lantai.domain.model.Media
import org.lantai.domain.model.MediaExtension

interface MediaRepository {
  fun findById(bookId: String): Media

  fun findByIdOrNull(bookId: String): Media?

  fun findAllBookIdsByLibraryIdAndMediaTypeAndWithMissingPageHash(
    libraryId: String,
    mediaTypes: Collection<String>,
    pageHashing: Int,
  ): Collection<String>

  fun getPagesSizes(bookIds: Collection<String>): Collection<Pair<String, Int>>

  fun findExtensionByIdOrNull(bookId: String): MediaExtension?

  fun insert(media: Media)

  fun insert(medias: Collection<Media>)

  fun update(media: Media)

  /**
   * Performs a full copy of a Media, including MediaExtension, pages, and files.
   */
  fun copy(
    fromBookId: String,
    toBookId: String,
  )

  fun delete(bookId: String)

  fun delete(bookIds: Collection<String>)

  fun count(): Long
}
