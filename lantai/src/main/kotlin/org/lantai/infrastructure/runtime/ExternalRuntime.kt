package org.lantai.infrastructure.runtime

data class RuntimeState(
  val kind: String,
  val displayName: String,
  val installed: Boolean = false,
  val running: Boolean = false,
  val usable: Boolean = false,
  val controllable: Boolean = false,
  val version: String? = null,
  val location: String? = null,
  val endpoint: String? = null,
  val message: String? = null,
)

interface ExternalRuntime {
  val kind: String

  val displayName: String

  fun probe(): RuntimeState

  fun start(): RuntimeState = probe()

  fun stop(): RuntimeState = probe()
}
