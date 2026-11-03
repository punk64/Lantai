package org.lantai.infrastructure.security.agent

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

class AgentTokenAuthenticationFilter(
  private val tokenProvider: AgentTokenProvider,
) : OncePerRequestFilter() {
  override fun doFilterInternal(
    request: HttpServletRequest,
    response: HttpServletResponse,
    filterChain: FilterChain,
  ) {
    val header = request.getHeader(AUTHORIZATION)
    if (header == null || !header.startsWith(BEARER_PREFIX)) {
      filterChain.doFilter(request, response)
      return
    }
    val candidate = header.substring(BEARER_PREFIX.length).trim()
    if (candidate.startsWith("lt_")) {
      filterChain.doFilter(request, response)
      return
    }
    if (!tokenProvider.matches(candidate)) {
      response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid agent token")
      return
    }
    val context = SecurityContextHolder.createEmptyContext()
    context.authentication =
      UsernamePasswordAuthenticationToken(AGENT_PRINCIPAL, null, listOf(SimpleGrantedAuthority(AGENT_ROLE)))
    SecurityContextHolder.setContext(context)
    request.setAttribute("__spring_security_session_mgmt_filter_applied", true)
    filterChain.doFilter(request, response)
  }

  companion object {
    private const val AUTHORIZATION = "Authorization"
    private const val BEARER_PREFIX = "Bearer "
    private const val AGENT_PRINCIPAL = "agent"
    private const val AGENT_ROLE = "ROLE_BULK_METADATA"
  }
}
