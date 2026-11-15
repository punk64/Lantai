package org.lantai.domain.persistence

import org.lantai.domain.model.Sidecar
import org.lantai.domain.model.SidecarStored
import java.net.URL

interface SidecarRepository {
  fun findAll(): Collection<SidecarStored>

  fun save(
    libraryId: String,
    sidecar: Sidecar,
  )

  fun deleteByLibraryIdAndUrls(
    libraryId: String,
    urls: Collection<URL>,
  )

  fun deleteByLibraryId(libraryId: String)

  fun countGroupedByLibraryId(): Map<String, Int>
}
