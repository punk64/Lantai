package org.lantai.infrastructure.runtime.install

import org.springframework.stereotype.Component

@Component
class RuntimeInstallRegistry(
  private val installers: List<RuntimeInstaller>,
) {
  fun all(): List<RuntimeInstaller> = installers

  fun get(kind: String): RuntimeInstaller? = installers.firstOrNull { it.kind == kind }

  fun detections(): List<RuntimeDetection> = installers.map { it.detect() }

  fun detection(kind: String): RuntimeDetection? = get(kind)?.detect()

  fun toggle(kind: String): RuntimeToggle? = installers.filterIsInstance<RuntimeToggle>().firstOrNull { it.kind == kind }

  fun toggles(): List<RuntimeToggle> = installers.filterIsInstance<RuntimeToggle>()
}
