package org.lantai.domain.persistence

import org.lantai.domain.model.BookAnnotation

interface BookAnnotationRepository {
  fun insert(annotation: BookAnnotation)

  fun findByIdOrNull(id: String): BookAnnotation?

  fun findAllByBookIdAndUserId(
    bookId: String,
    userId: String,
  ): List<BookAnnotation>

  fun update(annotation: BookAnnotation)

  fun delete(id: String)

  fun countByUserId(userId: String): Long
}
