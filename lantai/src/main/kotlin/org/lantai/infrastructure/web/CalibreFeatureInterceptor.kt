package org.lantai.infrastructure.web

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.lantai.infrastructure.calibre.CalibreFeature
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

@Component
class CalibreFeatureInterceptor : HandlerInterceptor {
  override fun preHandle(
    request: HttpServletRequest,
    response: HttpServletResponse,
    handler: Any,
  ): Boolean {
    if (CalibreFeature.ENABLED) return true
    response.status = HttpStatus.SERVICE_UNAVAILABLE.value()
    response.characterEncoding = Charsets.UTF_8.name()
    response.contentType = MediaType.APPLICATION_JSON_VALUE
    response.setHeader("Cache-Control", "no-store")
    response.writer.write(
      """{"status":503,"error":"SERVICE_UNAVAILABLE","message":"${CalibreFeature.MESSAGE}"}""",
    )
    return false
  }
}
