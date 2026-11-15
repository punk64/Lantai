package org.lantai.infrastructure.jooq.main

import org.lantai.domain.model.WantToRead
import org.lantai.domain.persistence.WantToReadRepository
import org.lantai.infrastructure.jooq.SplitDslDaoBase
import org.lantai.infrastructure.jooq.TempTable.Companion.withTempTable
import org.lantai.jooq.main.Tables
import org.lantai.jooq.main.tables.records.WantToReadRecord
import org.lantai.language.toCurrentTimeZone
import org.lantai.language.toUTC
import org.jooq.DSLContext
import org.jooq.Query
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.ZoneId

@Component
class WantToReadDao(
  dslRW: DSLContext,
  @Qualifier("dslContextRO") dslRO: DSLContext,
  @param:Value("#{@lantaiProperties.database.batchChunkSize}") private val batchSize: Int,
) : SplitDslDaoBase(dslRW, dslRO),
  WantToReadRepository {
  private val w = Tables.WANT_TO_READ

  override fun findByBookIdAndUserIdOrNull(
    bookId: String,
    userId: String,
  ): WantToRead? =
    dslRO
      .selectFrom(w)
      .where(w.BOOK_ID.eq(bookId).and(w.USER_ID.eq(userId)))
      .fetchOneInto(w)
      ?.toDomain()

  override fun findBookIdsByUserId(
    userId: String,
    limit: Int,
    offset: Int,
  ): List<String> =
    dslRO
      .select(w.BOOK_ID)
      .from(w)
      .where(w.USER_ID.eq(userId))
      .orderBy(w.CREATED_DATE.desc(), w.ID.desc())
      .limit(limit)
      .offset(offset)
      .fetch(w.BOOK_ID)

  override fun countByUserId(userId: String): Long =
    dslRO
      .selectCount()
      .from(w)
      .where(w.USER_ID.eq(userId))
      .fetchOne()
      ?.value1()
      ?.toLong()
      ?: 0L

  override fun findBookIdsByUserIdAndBookIds(
    userId: String,
    bookIds: Collection<String>,
  ): List<String> {
    if (bookIds.isEmpty()) return emptyList()
    return dslRO.withTempTable(batchSize, bookIds).use { tempTable ->
      dslRO
        .select(w.BOOK_ID)
        .from(w)
        .where(w.USER_ID.eq(userId).and(w.BOOK_ID.`in`(tempTable.selectTempStrings())))
        .fetch(w.BOOK_ID)
    }
  }

  @Transactional
  override fun save(wantToRead: WantToRead) {
    wantToRead.toQuery(dslRW).execute()
  }

  @Transactional
  override fun saveAll(wantToReads: Collection<WantToRead>) {
    wantToReads
      .map { it.toQuery(dslRW) }
      .chunked(batchSize)
      .forEach { chunk -> dslRW.batch(chunk).execute() }
  }

  private fun WantToRead.toQuery(dsl: DSLContext): Query =
    dsl
      .insertInto(w, w.ID, w.BOOK_ID, w.USER_ID, w.CREATED_DATE, w.LAST_MODIFIED_DATE)
      .values(id, bookId, userId, createdDate.toUTC(), lastModifiedDate.toUTC())
      .onDuplicateKeyUpdate()
      .set(w.LAST_MODIFIED_DATE, LocalDateTime.now(ZoneId.of("Z")))

  @Transactional
  override fun delete(
    bookId: String,
    userId: String,
  ) {
    dslRW.deleteFrom(w).where(w.BOOK_ID.eq(bookId).and(w.USER_ID.eq(userId))).execute()
  }

  @Transactional
  override fun deleteByBookId(bookId: String) {
    dslRW.deleteFrom(w).where(w.BOOK_ID.eq(bookId)).execute()
  }

  @Transactional
  override fun deleteByBookIds(bookIds: Collection<String>) {
    if (bookIds.isEmpty()) return
    dslRW.withTempTable(batchSize, bookIds).use { tempTable ->
      dslRW.deleteFrom(w).where(w.BOOK_ID.`in`(tempTable.selectTempStrings())).execute()
    }
  }

  @Transactional
  override fun deleteByUserId(userId: String) {
    dslRW.deleteFrom(w).where(w.USER_ID.eq(userId)).execute()
  }

  private fun WantToReadRecord.toDomain() =
    WantToRead(
      bookId = bookId,
      userId = userId,
      id = id,
      createdDate = createdDate.toCurrentTimeZone(),
      lastModifiedDate = lastModifiedDate.toCurrentTimeZone(),
    )
}
