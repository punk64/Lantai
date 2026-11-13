package org.lantai.domain.persistence

import org.lantai.domain.model.TrashEntry
import java.time.LocalDateTime

interface TrashEntryRepository {
  fun findByIdOrNull(id: String): TrashEntry?

  fun findAll(): Collection<TrashEntry>

  fun findAllByLibraryId(libraryId: String): Collection<TrashEntry>

  fun findOldestByLibraryId(
    libraryId: String,
    limit: Int,
  ): List<TrashEntry>

  fun findDeletedBefore(cutoff: LocalDateTime): List<TrashEntry>

  fun insert(entry: TrashEntry)

  fun delete(id: String)

  fun totalSizeByLibraryId(libraryId: String): Long
}
