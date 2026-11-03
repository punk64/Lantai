package org.lantai.infrastructure.calibre

object CalibreFeature {
  const val ENABLED = false
  const val KIND = "calibre"
  const val MESSAGE = "Calibre 功能适配开发中，暂时不可用"

  fun blocked(kind: String?): Boolean = !ENABLED && KIND.equals(kind?.trim(), ignoreCase = true)
}
