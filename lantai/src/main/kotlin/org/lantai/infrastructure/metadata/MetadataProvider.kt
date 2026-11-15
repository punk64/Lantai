package org.lantai.infrastructure.metadata

import org.lantai.domain.model.Library
import org.lantai.domain.model.MetadataPatchTarget

interface MetadataProvider {
  fun shouldLibraryHandlePatch(
    library: Library,
    target: MetadataPatchTarget,
  ): Boolean
}
