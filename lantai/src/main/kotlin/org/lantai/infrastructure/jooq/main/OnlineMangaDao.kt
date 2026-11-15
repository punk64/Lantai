package org.lantai.infrastructure.jooq.main

import org.lantai.domain.model.OnlineManga
import org.lantai.domain.persistence.OnlineMangaRepository
import org.lantai.infrastructure.jooq.SplitDslDaoBase
import org.lantai.jooq.main.Tables
import org.lantai.jooq.main.tables.records.OnlineMangaRecord
import org.lantai.language.toCurrentTimeZone
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.ZoneId

@Component
class OnlineMangaDao(
  dslRW: DSLContext,
  @Qualifier("dslContextRO") dslRO: DSLContext,
) : SplitDslDaoBase(dslRW, dslRO),
  OnlineMangaRepository {
  private val om = Tables.ONLINE_MANGA

  override fun findByIdOrNull(id: String): OnlineManga? =
    dslRO
      .selectFrom(om)
      .where(om.ID.eq(id))
      .fetchOne()
      ?.toDomain()

  override fun findBySeriesIdOrNull(seriesId: String): OnlineManga? =
    dslRO
      .selectFrom(om)
      .where(om.SERIES_ID.eq(seriesId))
      .fetchOne()
      ?.toDomain()

  override fun findByRemoteOrNull(
    sourceKind: String,
    remoteMangaId: String,
  ): OnlineManga? =
    dslRO
      .selectFrom(om)
      .where(om.SOURCE_KIND.eq(sourceKind))
      .and(om.REMOTE_MANGA_ID.eq(remoteMangaId))
      .fetchOne()
      ?.toDomain()

  override fun findAll(): Collection<OnlineManga> =
    dslRO
      .selectFrom(om)
      .fetch()
      .map { it.toDomain() }

  override fun findAllBySourceKind(sourceKind: String): Collection<OnlineManga> =
    dslRO
      .selectFrom(om)
      .where(om.SOURCE_KIND.eq(sourceKind))
      .fetch()
      .map { it.toDomain() }

  @Transactional
  override fun insert(manga: OnlineManga) {
    dslRW
      .insertInto(om)
      .set(om.ID, manga.id)
      .set(om.SERIES_ID, manga.seriesId)
      .set(om.SOURCE_KIND, manga.sourceKind)
      .set(om.CONTENT_KIND, manga.contentKind)
      .set(om.CONTENT_FORMAT, manga.contentFormat)
      .set(om.REMOTE_MANGA_ID, manga.remoteMangaId)
      .set(om.TITLE, manga.title)
      .set(om.COVER_URL, manga.coverUrl)
      .set(om.STATUS, manga.status)
      .set(om.LAST_SYNC_AT, manga.lastSyncAt)
      .execute()
  }

  @Transactional
  override fun update(manga: OnlineManga) {
    dslRW
      .update(om)
      .set(om.TITLE, manga.title)
      .set(om.CONTENT_KIND, manga.contentKind)
      .set(om.CONTENT_FORMAT, manga.contentFormat)
      .set(om.COVER_URL, manga.coverUrl)
      .set(om.STATUS, manga.status)
      .set(om.LAST_SYNC_AT, manga.lastSyncAt)
      .set(om.LAST_MODIFIED_DATE, LocalDateTime.now(ZoneId.of("Z")))
      .where(om.ID.eq(manga.id))
      .execute()
  }

  @Transactional
  override fun delete(id: String) {
    dslRW.deleteFrom(om).where(om.ID.eq(id)).execute()
  }

  override fun count(): Long = dslRO.fetchCount(om).toLong()

  private fun OnlineMangaRecord.toDomain() =
    OnlineManga(
      seriesId = seriesId,
      remoteMangaId = remoteMangaId,
      title = title,
      sourceKind = sourceKind,
      contentKind = contentKind,
      contentFormat = contentFormat,
      coverUrl = coverUrl,
      status = status,
      lastSyncAt = lastSyncAt?.toCurrentTimeZone(),
      id = id,
      createdDate = createdDate.toCurrentTimeZone(),
      lastModifiedDate = lastModifiedDate.toCurrentTimeZone(),
    )
}
