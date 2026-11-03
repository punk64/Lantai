package org.lantai.infrastructure.runtime

import org.lantai.infrastructure.online.SuwayomiProcessManager
import org.lantai.infrastructure.online.SuwayomiSourceProvider
import org.springframework.stereotype.Component

@Component
class SuwayomiRuntimeAdapter(
  private val processManager: SuwayomiProcessManager,
) : ExternalRuntime {
  override val kind: String = SuwayomiSourceProvider.KIND

  override val displayName: String = "Suwayomi"

  override fun probe(): RuntimeState {
    val status = processManager.status()
    return RuntimeState(
      kind = kind,
      displayName = displayName,
      installed = status.jarPresent,
      running = status.running,
      usable = status.jarPresent && status.running,
      controllable = true,
      version = status.version,
      location = status.jarPath,
      endpoint = status.endpoint,
      message = status.message,
    )
  }

  override fun start(): RuntimeState {
    processManager.start()
    return probe()
  }

  override fun stop(): RuntimeState {
    processManager.stop()
    return probe()
  }
}
