package org.lantai.infrastructure.jooq.main

import org.lantai.domain.model.BookProjection
import org.lantai.domain.persistence.BookProjectionRepository
import org.lantai.infrastructure.jooq.SplitDslDaoBase
import org.lantai.infrastructure.jooq.TempTable.Companion.withTempTable
import org.lantai.jooq.main.Tables
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.ZoneId

@Component
class BookProjectionDao(
  dslRW: DSLContext,
  @Qualifier("dslContextRO") dslRO: DSLContext,
  @param:Value("#{@lantaiProperties.database.batchChunkSize}") private val batchSize: Int,
) : SplitDslDaoBase(dslRW, dslRO),
  BookProjectionRepository {
  private val p = Tables.BOOK_PROJECTION

  override fun save(projection: BookProjection) {
    dslRW
      .insertInto(p, p.BOOK_ID, p.PROFILE, p.FILE_SIZE)
      .values(projection.bookId, projection.profile, projection.fileSize)
      .onDuplicateKeyUpdate()
      .set(p.FILE_SIZE, p.FILE_SIZE)
      .set(p.LAST_MODIFIED_DATE, LocalDateTime.now(ZoneId.of("Z")))
      .execute()
  }

  override fun delete(bookId: String) {
    dslRW.deleteFrom(p).where(p.BOOK_ID.eq(bookId)).execute()
  }

  @Transactional
  override fun delete(bookIds: Collection<String>) {
    dslRW.withTempTable(batchSize, bookIds).use { tempTable ->
      dslRW.deleteFrom(p).where(p.BOOK_ID.`in`(tempTable.selectTempStrings())).execute()
    }
  }
}
