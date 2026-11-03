package org.lantai.infrastructure.runtime

import org.springframework.stereotype.Component

@Component
class RuntimeRegistry(
  private val runtimes: List<ExternalRuntime>,
) {
  fun all(): List<ExternalRuntime> = runtimes

  fun get(kind: String): ExternalRuntime? = runtimes.firstOrNull { it.kind == kind }

  fun states(): List<RuntimeState> = runtimes.map { it.probe() }

  fun state(kind: String): RuntimeState? = get(kind)?.probe()
}
