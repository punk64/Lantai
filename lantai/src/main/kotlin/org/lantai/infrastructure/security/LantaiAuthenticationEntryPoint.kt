package org.lantai.infrastructure.security

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.stereotype.Component

@Component
class LantaiAuthenticationEntryPoint(
  private val objectMapper: ObjectMapper,
) : AuthenticationEntryPoint {
  override fun commence(
    request: HttpServletRequest,
    response: HttpServletResponse,
    authException: AuthenticationException,
  ) {
    with(response) {
      contentType = MediaType.APPLICATION_JSON_VALUE
      characterEncoding = Charsets.UTF_8.name()
      status = HttpStatus.UNAUTHORIZED.value()
      with(writer) {
        write(objectMapper.writeValueAsString(mapOf("error" to "Unauthorized", "message" to "需要登录")))
        flush()
      }
    }
  }
}
