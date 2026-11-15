package org.lantai.domain.persistence

import org.lantai.domain.model.OnlineManga

interface OnlineMangaRepository {
  fun findByIdOrNull(id: String): OnlineManga?

  fun findBySeriesIdOrNull(seriesId: String): OnlineManga?

  fun findByRemoteOrNull(
    sourceKind: String,
    remoteMangaId: String,
  ): OnlineManga?

  fun findAll(): Collection<OnlineManga>

  fun findAllBySourceKind(sourceKind: String): Collection<OnlineManga>

  fun insert(manga: OnlineManga)

  fun update(manga: OnlineManga)

  fun delete(id: String)

  fun count(): Long
}
