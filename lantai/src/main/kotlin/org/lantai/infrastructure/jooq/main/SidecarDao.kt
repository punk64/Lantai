package org.lantai.infrastructure.jooq.main

import org.lantai.domain.model.Sidecar
import org.lantai.domain.model.SidecarStored
import org.lantai.domain.persistence.SidecarRepository
import org.lantai.infrastructure.jooq.SplitDslDaoBase
import org.lantai.infrastructure.jooq.TempTable.Companion.withTempTable
import org.lantai.jooq.main.Tables
import org.lantai.jooq.main.tables.records.SidecarRecord
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.net.URL

@Component
class SidecarDao(
  dslRW: DSLContext,
  @Qualifier("dslContextRO") dslRO: DSLContext,
  @param:Value("#{@lantaiProperties.database.batchChunkSize}") private val batchSize: Int,
) : SplitDslDaoBase(dslRW, dslRO),
  SidecarRepository {
  private val sc = Tables.SIDECAR

  override fun findAll(): Collection<SidecarStored> = dslRO.selectFrom(sc).fetch().map { it.toDomain() }

  override fun save(
    libraryId: String,
    sidecar: Sidecar,
  ) {
    dslRW
      .insertInto(sc)
      .values(
        sidecar.url.toString(),
        sidecar.parentUrl.toString(),
        sidecar.lastModifiedTime,
        libraryId,
      ).onDuplicateKeyUpdate()
      .set(sc.LAST_MODIFIED_TIME, sidecar.lastModifiedTime)
      .set(sc.PARENT_URL, sidecar.parentUrl.toString())
      .set(sc.LIBRARY_ID, libraryId)
      .execute()
  }

  @Transactional
  override fun deleteByLibraryIdAndUrls(
    libraryId: String,
    urls: Collection<URL>,
  ) {
    dslRW.withTempTable(batchSize, urls.map { it.toString() }).use {
      dslRW
        .deleteFrom(sc)
        .where(sc.LIBRARY_ID.eq(libraryId))
        .and(sc.URL.`in`(it.selectTempStrings()))
        .execute()
    }
  }

  override fun deleteByLibraryId(libraryId: String) {
    dslRW
      .deleteFrom(sc)
      .where(sc.LIBRARY_ID.eq(libraryId))
      .execute()
  }

  override fun countGroupedByLibraryId(): Map<String, Int> =
    dslRO
      .select(sc.LIBRARY_ID, DSL.count(sc.URL))
      .from(sc)
      .groupBy(sc.LIBRARY_ID)
      .fetchMap(sc.LIBRARY_ID, DSL.count(sc.URL))

  private fun SidecarRecord.toDomain() =
    SidecarStored(
      url = URL(url),
      parentUrl = URL(parentUrl),
      lastModifiedTime = lastModifiedTime,
      libraryId = libraryId,
    )
}
