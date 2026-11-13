package org.lantai.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("api/lantai/v1/remember-me")
@Tag(name = "Lantai Session")
class LantaiRememberMeController(
  @param:Value("\${lantai.security.cookie-secure:false}") private val cookieSecure: Boolean,
) {
  @DeleteMapping
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "Clear the remember-me cookie of the current browser")
  fun clear(response: HttpServletResponse) {
    val secure = if (cookieSecure) "; Secure" else ""
    response.addHeader(
      HttpHeaders.SET_COOKIE,
      "lantai-remember-me=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax$secure",
    )
  }
}
