package org.lantai.infrastructure.metadata

import org.lantai.domain.model.BookMetadataPatch
import org.lantai.domain.model.BookMetadataPatchCapability
import org.lantai.domain.model.BookWithMedia

interface BookMetadataProvider : MetadataProvider {
  val capabilities: Set<BookMetadataPatchCapability>

  fun getBookMetadataFromBook(book: BookWithMedia): BookMetadataPatch?
}
