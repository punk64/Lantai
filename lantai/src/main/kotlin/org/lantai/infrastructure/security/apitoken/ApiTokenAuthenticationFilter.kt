package org.lantai.infrastructure.security.apitoken

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.lantai.domain.model.ApiToken
import org.lantai.domain.persistence.ApiTokenRepository
import org.lantai.domain.persistence.LantaiUserRepository
import org.lantai.infrastructure.security.LantaiPrincipal
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository
import org.springframework.web.filter.OncePerRequestFilter
import java.net.URI
import java.time.LocalDateTime

class ApiTokenAuthenticationFilter(
  private val apiTokenRepository: ApiTokenRepository,
  private val userRepository: LantaiUserRepository,
  private val scopePolicy: ApiTokenScopePolicy,
) : OncePerRequestFilter() {
  private val securityContextRepository = RequestAttributeSecurityContextRepository()
  private val detailsSource = WebAuthenticationDetailsSource()

  override fun doFilterInternal(
    request: HttpServletRequest,
    response: HttpServletResponse,
    filterChain: FilterChain,
  ) {
    val raw = readToken(request)
    if (raw == null) {
      filterChain.doFilter(request, response)
      return
    }
    val token = apiTokenRepository.findByTokenValueOrNull(raw)
    if (token == null) {
      reject(response, HttpServletResponse.SC_UNAUTHORIZED, "无效的 API 令牌")
      return
    }
    if (!token.enabled) {
      reject(response, HttpServletResponse.SC_UNAUTHORIZED, "API 令牌已停用")
      return
    }
    if (token.isExpired()) {
      reject(response, HttpServletResponse.SC_UNAUTHORIZED, "API 令牌已过期")
      return
    }
    val user = userRepository.findByIdOrNull(token.ownerId)
    if (user == null || !user.enabled) {
      reject(response, HttpServletResponse.SC_UNAUTHORIZED, "令牌归属账号不可用")
      return
    }
    if (!scopePolicy.allows(token.scopes, request.method, normalizedPath(request))) {
      reject(response, HttpServletResponse.SC_FORBIDDEN, "API 令牌无权访问该接口")
      return
    }

    val principal = LantaiPrincipal(user, name = token.name, authorityRoles = rolesFor(token.scopes))
    val authentication = UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
    authentication.details = detailsSource.buildDetails(request)
    val context =
      SecurityContextHolder.createEmptyContext().apply {
        this.authentication = authentication
      }
    SecurityContextHolder.setContext(context)
    securityContextRepository.saveContext(context, request, response)
    request.setAttribute("__spring_security_session_mgmt_filter_applied", true)
    runCatching { apiTokenRepository.recordUsage(token.id, LocalDateTime.now(), clientIp(request)) }
    filterChain.doFilter(request, response)
  }

  private fun normalizedPath(request: HttpServletRequest): String {
    val raw = request.requestURI
    val decoded =
      runCatching { java.net.URLDecoder.decode(raw, Charsets.UTF_8) }.getOrDefault(raw)
    return runCatching { URI.create(decoded).normalize().path.ifEmpty { raw } }
      .getOrDefault(raw)
      .replace(Regex("/{2,}"), "/")
  }

  private fun readToken(request: HttpServletRequest): String? {
    val header = request.getHeader(ApiTokenGenerator.HEADER)?.trim()?.takeIf { it.isNotEmpty() }
    if (header != null) return header.removePrefix("Bearer ").trim()
    val authorization = request.getHeader("Authorization")?.trim() ?: return null
    if (!authorization.startsWith("Bearer ")) return null
    val candidate = authorization.removePrefix("Bearer ").trim()
    return candidate.takeIf { it.startsWith(ApiTokenGenerator.PREFIX) }
  }

  private fun reject(
    response: HttpServletResponse,
    status: Int,
    message: String,
  ) {
    SecurityContextHolder.clearContext()
    response.status = status
    response.contentType = "application/json;charset=UTF-8"
    response.writer.write("""{"error":"$message"}""")
    response.writer.flush()
  }

  private fun clientIp(request: HttpServletRequest): String? =
    request.getHeader("X-Forwarded-For")?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
      ?: request.getHeader("X-Real-IP")?.trim()?.takeIf { it.isNotEmpty() }
      ?: request.remoteAddr

  private fun rolesFor(scopes: Set<ApiToken.Scope>): Set<String> =
    buildSet {
      scopes.forEach { scope ->
        when (scope) {
          ApiToken.Scope.ADMIN -> add("ADMIN")
          ApiToken.Scope.METADATA_READ, ApiToken.Scope.METADATA_WRITE -> {
            add("ADMIN")
            add("BULK_METADATA")
          }
          ApiToken.Scope.BOOKS_DOWNLOAD -> add("FILE_DOWNLOAD")
          ApiToken.Scope.PAGES_STREAM -> add("PAGE_STREAMING")
          ApiToken.Scope.KOBO_SYNC -> add("KOBO_SYNC")
          ApiToken.Scope.KOREADER_SYNC -> add("KOREADER_SYNC")
          ApiToken.Scope.SCRAPE_READ -> add("SCRAPE_READ")
          ApiToken.Scope.SCRAPE_SUBMIT -> add("SCRAPE_SUBMIT")
          else -> Unit
        }
      }
    }
}
