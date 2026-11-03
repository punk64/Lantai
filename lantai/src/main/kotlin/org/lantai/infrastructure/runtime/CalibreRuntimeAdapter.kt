package org.lantai.infrastructure.runtime

import org.lantai.infrastructure.calibre.CalibreRuntime
import org.springframework.stereotype.Component

@Component
class CalibreRuntimeAdapter(
  private val runtime: CalibreRuntime,
) : ExternalRuntime {
  override val kind: String = "calibre"

  override val displayName: String = "Calibre"

  override fun probe(): RuntimeState {
    val status = runtime.status()
    return RuntimeState(
      kind = kind,
      displayName = displayName,
      installed = status.installed,
      running = false,
      usable = status.available,
      version = status.version,
      location = status.executablePath,
      endpoint = null,
      message = status.message,
    )
  }
}
