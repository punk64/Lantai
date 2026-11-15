package org.lantai.interfaces.api.kobo.persistence

import org.lantai.interfaces.api.kobo.dto.KoboBookMetadataDto

interface KoboDtoRepository {
  fun findBookMetadataByIds(
    bookIds: Collection<String>,
  ): Collection<KoboBookMetadataDto>
}
