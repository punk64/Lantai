package org.lantai.infrastructure.sidecar

import org.lantai.domain.model.Sidecar

interface SidecarBookConsumer {
  fun getSidecarBookType(): Sidecar.Type

  fun getSidecarBookPrefilter(): List<Regex>

  fun isSidecarBookMatch(
    basename: String,
    sidecar: String,
  ): Boolean
}
