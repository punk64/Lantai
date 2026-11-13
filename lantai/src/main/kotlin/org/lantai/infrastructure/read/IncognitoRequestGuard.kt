package org.lantai.infrastructure.read

import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

@Component
class IncognitoRequestGuard {
  fun isOn(): Boolean {
    val attributes = RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes ?: return false
    return attributes.request.getHeader(HEADER) == "1"
  }

  companion object {
    const val HEADER = "X-Lantai-Incognito"
  }
}
