package org.lantai.domain.persistence

import org.lantai.domain.model.WantToRead

interface WantToReadRepository {
  fun findByBookIdAndUserIdOrNull(
    bookId: String,
    userId: String,
  ): WantToRead?

  fun findBookIdsByUserId(
    userId: String,
    limit: Int,
    offset: Int,
  ): List<String>

  fun countByUserId(userId: String): Long

  fun findBookIdsByUserIdAndBookIds(
    userId: String,
    bookIds: Collection<String>,
  ): List<String>

  fun save(wantToRead: WantToRead)

  fun saveAll(wantToReads: Collection<WantToRead>)

  fun delete(
    bookId: String,
    userId: String,
  )

  fun deleteByBookId(bookId: String)

  fun deleteByBookIds(bookIds: Collection<String>)

  fun deleteByUserId(userId: String)
}
