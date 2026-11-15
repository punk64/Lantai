package org.lantai.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.lantai.infrastructure.jooq.main.LantaiAuthActivityDao
import org.lantai.infrastructure.security.LantaiPrincipal
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDateTime
import java.time.ZoneId

data class AuthActivityDeleteItem(
  val ip: String? = null,
  val at: String? = null,
)

data class AuthActivityDeleteRequest(
  val all: Boolean = false,
  val items: List<AuthActivityDeleteItem> = emptyList(),
)

data class AuthActivityDeleteResult(
  val deleted: Int,
)

@RestController
@RequestMapping("api/lantai/v1/auth-activity", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Lantai Auth Activity")
class LantaiAuthActivityController(
  private val lantaiAuthActivityDao: LantaiAuthActivityDao,
) {
  @DeleteMapping
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "Delete authentication activity entries")
  fun delete(
    @AuthenticationPrincipal principal: LantaiPrincipal,
    @RequestBody request: AuthActivityDeleteRequest,
  ): AuthActivityDeleteResult {
    val scope = if (principal.user.isAdmin) null else principal.user
    if (request.all) return AuthActivityDeleteResult(lantaiAuthActivityDao.deleteAll(scope))
    val items =
      request.items.mapNotNull { item ->
        val raw = item.at?.trim()?.removeSuffix("Z") ?: return@mapNotNull null
        val parsed = runCatching { LocalDateTime.parse(raw) }.getOrNull() ?: return@mapNotNull null
        val utc = parsed.atZone(ZoneId.systemDefault()).withZoneSameInstant(ZoneId.of("Z")).toLocalDateTime()
        item.ip to utc
      }
    return AuthActivityDeleteResult(lantaiAuthActivityDao.deleteEntries(scope, items))
  }
}
