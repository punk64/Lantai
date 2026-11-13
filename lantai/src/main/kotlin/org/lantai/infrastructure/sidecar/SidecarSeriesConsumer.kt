package org.lantai.infrastructure.sidecar

import org.lantai.domain.model.Sidecar

interface SidecarSeriesConsumer {
  fun getSidecarSeriesType(): Sidecar.Type

  fun getSidecarSeriesFilenames(): List<String>
}
