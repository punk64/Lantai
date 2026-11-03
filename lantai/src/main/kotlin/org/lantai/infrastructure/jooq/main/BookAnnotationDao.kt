package org.lantai.infrastructure.jooq.main

import org.lantai.domain.model.BookAnnotation
import org.lantai.domain.persistence.BookAnnotationRepository
import org.lantai.infrastructure.jooq.SplitDslDaoBase
import org.lantai.jooq.main.Tables
import org.lantai.jooq.main.tables.records.BookAnnotationRecord
import org.lantai.language.toCurrentTimeZone
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.time.ZoneId

@Component
class BookAnnotationDao(
  dslRW: DSLContext,
  @Qualifier("dslContextRO") dslRO: DSLContext,
) : SplitDslDaoBase(dslRW, dslRO),
  BookAnnotationRepository {
  private val a = Tables.BOOK_ANNOTATION

  override fun insert(annotation: BookAnnotation) {
    val now = LocalDateTime.now(ZoneId.of("Z"))
    dslRW
      .insertInto(a)
      .set(a.ID, annotation.id)
      .set(a.BOOK_ID, annotation.bookId)
      .set(a.USER_ID, annotation.userId)
      .set(a.HREF, annotation.href)
      .set(a.FRAGMENT, annotation.fragment)
      .set(a.PROGRESSION, annotation.progression?.toFloat())
      .set(a.TEXT_BEFORE, annotation.textBefore)
      .set(a.TEXT_HIGHLIGHT, annotation.textHighlight)
      .set(a.TEXT_AFTER, annotation.textAfter)
      .set(a.COLOR, annotation.color)
      .set(a.MARKER, annotation.marker)
      .set(a.NOTE, annotation.note)
      .set(a.SELECTION, annotation.selection)
      .set(a.CREATED_DATE, now)
      .set(a.LAST_MODIFIED_DATE, now)
      .execute()
  }

  override fun findByIdOrNull(id: String): BookAnnotation? =
    dslRO
      .selectFrom(a)
      .where(a.ID.eq(id))
      .fetchOne()
      ?.toDomain()

  override fun findAllByBookIdAndUserId(
    bookId: String,
    userId: String,
  ): List<BookAnnotation> =
    dslRO
      .selectFrom(a)
      .where(a.BOOK_ID.eq(bookId))
      .and(a.USER_ID.eq(userId))
      .orderBy(a.CREATED_DATE.asc())
      .fetch()
      .map { it.toDomain() }

  override fun update(annotation: BookAnnotation) {
    dslRW
      .update(a)
      .set(a.HREF, annotation.href)
      .set(a.FRAGMENT, annotation.fragment)
      .set(a.PROGRESSION, annotation.progression?.toFloat())
      .set(a.TEXT_BEFORE, annotation.textBefore)
      .set(a.TEXT_HIGHLIGHT, annotation.textHighlight)
      .set(a.TEXT_AFTER, annotation.textAfter)
      .set(a.COLOR, annotation.color)
      .set(a.MARKER, annotation.marker)
      .set(a.NOTE, annotation.note)
      .set(a.SELECTION, annotation.selection)
      .set(a.LAST_MODIFIED_DATE, LocalDateTime.now(ZoneId.of("Z")))
      .where(a.ID.eq(annotation.id))
      .execute()
  }

  override fun delete(id: String) {
    dslRW
      .deleteFrom(a)
      .where(a.ID.eq(id))
      .execute()
  }

  override fun countByUserId(userId: String): Long = dslRO.fetchCount(a, a.USER_ID.eq(userId)).toLong()

  private fun BookAnnotationRecord.toDomain() =
    BookAnnotation(
      bookId = bookId,
      userId = userId,
      href = href,
      fragment = fragment.orEmpty(),
      progression = progression?.toDouble(),
      textBefore = textBefore.orEmpty(),
      textHighlight = textHighlight.orEmpty(),
      textAfter = textAfter.orEmpty(),
      color = color,
      marker = marker,
      note = note.orEmpty(),
      selection = selection,
      id = id,
      createdDate = createdDate.toCurrentTimeZone(),
      lastModifiedDate = lastModifiedDate.toCurrentTimeZone(),
    )
}
