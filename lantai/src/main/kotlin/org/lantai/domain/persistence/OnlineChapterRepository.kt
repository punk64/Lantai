package org.lantai.domain.persistence

import org.lantai.domain.model.OnlineChapter

interface OnlineChapterRepository {
  fun findByIdOrNull(id: String): OnlineChapter?

  fun findByBookIdOrNull(bookId: String): OnlineChapter?

  fun findAllByMangaId(mangaId: String): Collection<OnlineChapter>

  fun findAllByBookIds(bookIds: Collection<String>): Collection<OnlineChapter>

  fun insert(chapters: Collection<OnlineChapter>)

  fun update(chapter: OnlineChapter)

  fun update(chapters: Collection<OnlineChapter>)

  fun deleteByMangaId(mangaId: String)

  fun count(): Long
}
