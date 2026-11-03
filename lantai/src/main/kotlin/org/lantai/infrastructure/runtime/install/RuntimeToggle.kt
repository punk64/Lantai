package org.lantai.infrastructure.runtime.install

data class RuntimeToggleResult(
  val kind: String,
  val enabled: Boolean,
  val running: Boolean,
  val message: String? = null,
)

interface RuntimeToggle {
  val kind: String

  fun isEnabled(): Boolean

  fun setEnabled(value: Boolean): RuntimeToggleResult
}
