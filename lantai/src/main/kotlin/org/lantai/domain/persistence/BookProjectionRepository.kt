package org.lantai.domain.persistence

import org.lantai.domain.model.BookProjection

interface BookProjectionRepository {
  fun save(projection: BookProjection)

  fun delete(bookId: String)

  fun delete(bookIds: Collection<String>)
}
