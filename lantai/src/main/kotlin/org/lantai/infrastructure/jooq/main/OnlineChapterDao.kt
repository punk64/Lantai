package org.lantai.infrastructure.jooq.main

import org.lantai.domain.model.OnlineChapter
import org.lantai.domain.persistence.OnlineChapterRepository
import org.lantai.infrastructure.jooq.SplitDslDaoBase
import org.lantai.jooq.main.Tables
import org.lantai.jooq.main.tables.records.OnlineChapterRecord
import org.lantai.language.toCurrentTimeZone
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.ZoneId

@Component
class OnlineChapterDao(
  dslRW: DSLContext,
  @Qualifier("dslContextRO") dslRO: DSLContext,
) : SplitDslDaoBase(dslRW, dslRO),
  OnlineChapterRepository {
  private val oc = Tables.ONLINE_CHAPTER
  private val batchSize = 500

  override fun findByIdOrNull(id: String): OnlineChapter? =
    dslRO
      .selectFrom(oc)
      .where(oc.ID.eq(id))
      .fetchOne()
      ?.toDomain()

  override fun findByBookIdOrNull(bookId: String): OnlineChapter? =
    dslRO
      .selectFrom(oc)
      .where(oc.BOOK_ID.eq(bookId))
      .fetchOne()
      ?.toDomain()

  override fun findAllByMangaId(mangaId: String): Collection<OnlineChapter> =
    dslRO
      .selectFrom(oc)
      .where(oc.MANGA_ID.eq(mangaId))
      .orderBy(oc.IDX)
      .fetch()
      .map { it.toDomain() }

  override fun findAllByBookIds(bookIds: Collection<String>): Collection<OnlineChapter> {
    if (bookIds.isEmpty()) return emptyList()
    return dslRO
      .selectFrom(oc)
      .where(oc.BOOK_ID.`in`(bookIds))
      .fetch()
      .map { it.toDomain() }
  }

  @Transactional
  override fun insert(chapters: Collection<OnlineChapter>) {
    if (chapters.isEmpty()) return
    chapters.chunked(batchSize).forEach { chunk ->
      dslRW
        .batch(
          dslRW
            .insertInto(
              oc,
              oc.ID,
              oc.MANGA_ID,
              oc.BOOK_ID,
              oc.IDX,
              oc.REMOTE_ID,
              oc.REMOTE_CHAPTER_INDEX,
              oc.TITLE,
              oc.PAGE_COUNT,
              oc.DOWNLOADED,
              oc.CONTENT_KIND,
            ).values(null as String?, null, null, null, null, null, null, null, null, null),
        ).also { step ->
          chunk.forEach { chapter ->
            step.bind(
              chapter.id,
              chapter.mangaId,
              chapter.bookId,
              chapter.idx,
              chapter.remoteId,
              chapter.remoteChapterIndex,
              chapter.title,
              chapter.pageCount,
              chapter.downloaded,
              chapter.contentKind,
            )
          }
        }.execute()
    }
  }

  @Transactional
  override fun update(chapter: OnlineChapter) {
    update(listOf(chapter))
  }

  @Transactional
  override fun update(chapters: Collection<OnlineChapter>) {
    if (chapters.isEmpty()) return
    val now = LocalDateTime.now(ZoneId.of("Z"))
    chapters.forEach { chapter ->
      dslRW
        .update(oc)
        .set(oc.TITLE, chapter.title)
        .set(oc.PAGE_COUNT, chapter.pageCount)
        .set(oc.DOWNLOADED, chapter.downloaded)
        .set(oc.CONTENT_KIND, chapter.contentKind)
        .set(oc.LAST_MODIFIED_DATE, now)
        .where(oc.ID.eq(chapter.id))
        .execute()
    }
  }

  @Transactional
  override fun deleteByMangaId(mangaId: String) {
    dslRW.deleteFrom(oc).where(oc.MANGA_ID.eq(mangaId)).execute()
  }

  override fun count(): Long = dslRO.fetchCount(oc).toLong()

  private fun OnlineChapterRecord.toDomain() =
    OnlineChapter(
      mangaId = mangaId,
      bookId = bookId,
      idx = idx,
      remoteId = remoteId,
      remoteChapterIndex = remoteChapterIndex,
      title = title,
      pageCount = pageCount,
      downloaded = downloaded,
      contentKind = contentKind,
      id = id,
      createdDate = createdDate.toCurrentTimeZone(),
      lastModifiedDate = lastModifiedDate.toCurrentTimeZone(),
    )
}
