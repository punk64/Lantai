package org.lantai.infrastructure.web

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.lantai.domain.model.AuditLog
import org.lantai.domain.service.AuditRecorder
import org.lantai.infrastructure.security.LantaiPrincipal
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

private val logger = KotlinLogging.logger {}

@Component
class AuditInterceptor(
  private val auditRecorder: AuditRecorder,
) : HandlerInterceptor {
  override fun afterCompletion(
    request: HttpServletRequest,
    response: HttpServletResponse,
    handler: Any,
    ex: Exception?,
  ) {
    try {
      val method = request.method
      if (method !in AUDITABLE_METHODS) return

      val path = request.requestURI.removePrefix(request.contextPath)
      if (!path.startsWith("/api/")) return
      if (EXCLUDED_PATHS.any { path.startsWith(it) }) return

      val principal = SecurityContextHolder.getContext().authentication?.principal as? LantaiPrincipal ?: return

      val target = AuditNaming.resolve(method, path)
      auditRecorder.record(
        AuditLog(
          action = target.action,
          method = method,
          path = path,
          status = response.status,
          actorId = principal.user.id,
          actorEmail = principal.user.email,
          targetType = target.targetType,
          targetId = target.targetId,
          query = request.queryString?.take(MAX_QUERY_LENGTH),
          ip = request.remoteAddr,
          userAgent = request.getHeader("User-Agent")?.take(MAX_USER_AGENT_LENGTH),
        ),
      )
    } catch (e: Exception) {
      logger.warn(e) { "Failed to record audit entry" }
    }
  }

  private companion object {
    val AUDITABLE_METHODS = setOf("POST", "PUT", "PATCH", "DELETE")
    val EXCLUDED_PATHS = listOf("/api/logout")
    const val MAX_QUERY_LENGTH = 512
    const val MAX_USER_AGENT_LENGTH = 256
  }
}

internal data class AuditTarget(
  val action: String,
  val targetType: String?,
  val targetId: String?,
)

internal object AuditNaming {
  private val RESOURCE_NAMES =
    mapOf(
      "users" to "USER",
      "libraries" to "LIBRARY",
      "series" to "SERIES",
      "books" to "BOOK",
      "collections" to "COLLECTION",
      "readlists" to "READLIST",
      "announcements" to "ANNOUNCEMENT",
      "fonts" to "FONT",
      "settings" to "SETTINGS",
      "tasks" to "TASK",
      "trash" to "TRASH",
      "online" to "ONLINE",
      "scrape" to "SCRAPE",
      "logs" to "LOG",
      "audit" to "AUDIT",
      "reading" to "READING",
      "suwayomi" to "SUWAYOMI",
      "client-settings" to "CLIENT_SETTINGS",
      "sync-points" to "SYNC_POINT",
      "page-hashes" to "PAGE_HASH",
      "transient-books" to "TRANSIENT_BOOK",
      "historical-events" to "HISTORICAL_EVENT",
      "oauth2" to "OAUTH2",
    )

  private val ID_PATTERN = Regex("""^[0-9A-Z]{6,}$""")

  fun resolve(
    method: String,
    path: String,
  ): AuditTarget {
    val segments =
      path
        .split('/')
        .filter { it.isNotEmpty() }
        .dropWhile { it == "api" || it.matches(Regex("""^v\d+$""")) }
        .toMutableList()

    if (segments.firstOrNull() == "lantai") segments.removeAt(0)

    val resource = segments.firstOrNull() ?: return AuditTarget("OTHER_${verb(method, false)}", null, null)
    val rest = segments.drop(1)
    val resourceName = RESOURCE_NAMES[resource] ?: resource.uppercase().replace('-', '_')
    val targetId = rest.firstOrNull { ID_PATTERN.matches(it) }

    val action =
      when {
        rest.any { it == "password" } && method != "DELETE" -> "${resourceName}_PASSWORD_CHANGE"
        rest.any { it == "scan" } && method == "POST" -> "${resourceName}_SCAN"
        rest.any { it == "restore" } -> "${resourceName}_RESTORE"
        else -> "${resourceName}_${verb(method, targetId != null)}"
      }

    return AuditTarget(action, resourceName, targetId)
  }

  private fun verb(
    method: String,
    hasTarget: Boolean,
  ): String =
    when (method) {
      "POST" -> if (hasTarget) "EXECUTE" else "CREATE"
      "PUT", "PATCH" -> "UPDATE"
      "DELETE" -> "DELETE"
      else -> method
    }
}
