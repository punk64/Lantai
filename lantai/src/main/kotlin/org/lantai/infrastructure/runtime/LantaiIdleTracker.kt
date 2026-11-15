package org.lantai.infrastructure.runtime

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.concurrent.atomic.AtomicLong

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class LantaiIdleTracker : OncePerRequestFilter() {
  private val lastRequest = AtomicLong(System.currentTimeMillis())

  fun lastRequestAt(): Long = lastRequest.get()

  fun idleMs(): Long = System.currentTimeMillis() - lastRequest.get()

  fun touch() {
    lastRequest.set(System.currentTimeMillis())
  }

  override fun shouldNotFilter(request: HttpServletRequest): Boolean =
    request.requestURI.contains("/api/lantai/v1/memory")

  override fun doFilterInternal(
    request: HttpServletRequest,
    response: HttpServletResponse,
    filterChain: FilterChain,
  ) {
    lastRequest.set(System.currentTimeMillis())
    filterChain.doFilter(request, response)
  }
}
