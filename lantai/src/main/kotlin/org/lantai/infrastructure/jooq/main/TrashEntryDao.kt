package org.lantai.infrastructure.jooq.main

import org.lantai.domain.model.TrashEntry
import org.lantai.domain.persistence.TrashEntryRepository
import org.lantai.infrastructure.jooq.SplitDslDaoBase
import org.lantai.jooq.main.Tables
import org.lantai.jooq.main.tables.records.TrashEntryRecord
import org.lantai.language.toCurrentTimeZone
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.time.LocalDateTime

@Component
class TrashEntryDao(
  dslRW: DSLContext,
  @Qualifier("dslContextRO") dslRO: DSLContext,
) : SplitDslDaoBase(dslRW, dslRO),
  TrashEntryRepository {
  private val t = Tables.TRASH_ENTRY

  override fun findByIdOrNull(id: String): TrashEntry? =
    dslRO
      .selectFrom(t)
      .where(t.ID.eq(id))
      .fetchOneInto(t)
      ?.toDomain()

  override fun findAll(): Collection<TrashEntry> =
    dslRO
      .selectFrom(t)
      .orderBy(t.DELETED_DATE.desc())
      .fetchInto(t)
      .map { it.toDomain() }

  override fun findAllByLibraryId(libraryId: String): Collection<TrashEntry> =
    dslRO
      .selectFrom(t)
      .where(t.LIBRARY_ID.eq(libraryId))
      .orderBy(t.DELETED_DATE.desc())
      .fetchInto(t)
      .map { it.toDomain() }

  override fun findOldestByLibraryId(
    libraryId: String,
    limit: Int,
  ): List<TrashEntry> =
    dslRO
      .selectFrom(t)
      .where(t.LIBRARY_ID.eq(libraryId))
      .orderBy(t.DELETED_DATE.asc())
      .limit(limit)
      .fetchInto(t)
      .map { it.toDomain() }

  override fun findDeletedBefore(cutoff: LocalDateTime): List<TrashEntry> =
    dslRO
      .selectFrom(t)
      .where(t.DELETED_DATE.lt(cutoff))
      .orderBy(t.DELETED_DATE.asc())
      .fetchInto(t)
      .map { it.toDomain() }

  override fun insert(entry: TrashEntry) {
    dslRW
      .insertInto(t)
      .set(t.ID, entry.id)
      .set(t.ITEM_TYPE, entry.itemType.toString())
      .set(t.LIBRARY_ID, entry.libraryId)
      .set(t.SERIES_ID, entry.seriesId)
      .set(t.BOOK_ID, entry.bookId)
      .set(t.ORIGINAL_PATH, entry.originalPath)
      .set(t.TRASHED_PATH, entry.trashedPath)
      .set(t.SIZE_BYTES, entry.sizeBytes)
      .set(t.DELETED_DATE, entry.deletedDate)
      .execute()
  }

  override fun delete(id: String) {
    dslRW.deleteFrom(t).where(t.ID.eq(id)).execute()
  }

  override fun totalSizeByLibraryId(libraryId: String): Long =
    dslRO
      .select(DSL.sum(t.SIZE_BYTES))
      .from(t)
      .where(t.LIBRARY_ID.eq(libraryId))
      .fetchOne(0, Long::class.javaObjectType) ?: 0L

  private fun TrashEntryRecord.toDomain() =
    TrashEntry(
      itemType = TrashEntry.ItemType.valueOf(itemType),
      libraryId = libraryId,
      originalPath = originalPath,
      trashedPath = trashedPath,
      sizeBytes = sizeBytes,
      seriesId = seriesId,
      bookId = bookId,
      deletedDate = deletedDate.toCurrentTimeZone(),
      id = id,
    )
}
