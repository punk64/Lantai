package org.lantai.interfaces.api.rest

import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.apache.commons.lang3.RandomStringUtils
import org.lantai.domain.model.LantaiUser
import org.lantai.domain.model.UserEmailAlreadyExistsException
import org.lantai.domain.model.UserRoles
import org.lantai.domain.persistence.AuthenticationActivityRepository
import org.lantai.domain.persistence.LantaiUserRepository
import org.lantai.domain.service.LantaiUserLifecycle
import org.lantai.infrastructure.security.LantaiPrincipal
import org.lantai.interfaces.api.rest.dto.AgeRestrictionDto
import org.lantai.interfaces.api.rest.dto.ApiKeyDto
import org.lantai.interfaces.api.rest.dto.AuthenticationActivityDto
import org.lantai.interfaces.api.rest.dto.redacted
import org.lantai.interfaces.api.rest.dto.toDto
import org.springdoc.core.converters.models.PageableAsQueryParam
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.session.SessionRegistry
import org.springframework.session.FindByIndexNameSessionRepository
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.LocalDateTime

@RestController
@RequestMapping("api/lantai/v1/users", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Lantai Users")
class LantaiUserAdminController(
  private val userRepository: LantaiUserRepository,
  private val userLifecycle: LantaiUserLifecycle,
  private val authenticationActivityRepository: AuthenticationActivityRepository,
  private val sessionRegistry: SessionRegistry,
  private val sessionRepository: FindByIndexNameSessionRepository<*>,
) {
  @GetMapping
  @PageableAsQueryParam
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "List and search users")
  fun search(
    @RequestParam(name = "q", required = false) q: String?,
    @RequestParam(name = "role", required = false) role: String?,
    @RequestParam(name = "enabled", required = false) enabled: Boolean?,
    @Parameter(hidden = true) pageable: Pageable,
  ): Page<AdminUserDto> {
    val normalizedRole =
      role?.takeIf { it.isNotBlank() }?.let {
        val upper = it.trim().uppercase()
        if (UserRoles.values().none { role -> role.name == upper })
          throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown role: $it")
        upper
      }
    return userRepository.search(q, normalizedRole, enabled, pageable).map { it.toAdminDto() }
  }

  @GetMapping("roles")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "List assignable roles")
  fun roles(): List<RoleInfoDto> =
    UserRoles.values().map {
      RoleInfoDto(
        name = it.name,
        description = ROLE_DESCRIPTIONS[it] ?: it.name,
        default = it in DEFAULT_ROLES,
      )
    }

  @GetMapping("{id}")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Retrieve a user with its activity summary")
  fun get(
    @PathVariable id: String,
  ): AdminUserDto = findUser(id).toAdminDto(detailed = true)

  @PutMapping("{id}/profile")
  @PreAuthorize("hasRole('ADMIN') or #principal.user.id == #id")
  @Operation(summary = "Update the username and email of a user")
  fun updateProfile(
    @PathVariable id: String,
    @AuthenticationPrincipal principal: LantaiPrincipal,
    @RequestBody body: ProfileUpdateDto,
  ): AdminUserDto {
    val user = findUser(id)
    val updated =
      try {
        userLifecycle.updateProfile(user, body.username, body.email)
      } catch (e: UserEmailAlreadyExistsException) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message)
      } catch (e: IllegalArgumentException) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message)
      }
    return updated.toAdminDto()
  }

  @PutMapping("{id}/roles")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Replace the roles of a user")
  fun updateRoles(
    @PathVariable id: String,
    @RequestBody body: RolesUpdateDto,
    @AuthenticationPrincipal principal: LantaiPrincipal,
  ): AdminUserDto {
    val user = findUser(id)
    val roles = parseRoles(body.roles)

    if (user.isAdmin && UserRoles.ADMIN !in roles) {
      requireOtherUser(principal, id)
      requireAnotherAdmin()
    }

    userLifecycle.updateUser(user.copy(roles = roles))
    return findUser(id).toAdminDto(detailed = true)
  }

  @PutMapping("{id}/status")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Enable or disable a user")
  fun updateStatus(
    @PathVariable id: String,
    @RequestBody body: UserStatusDto,
    @AuthenticationPrincipal principal: LantaiPrincipal,
  ): AdminUserDto {
    val user = findUser(id)
    requireOtherUser(principal, id)
    if (!body.enabled && user.isAdmin) requireAnotherAdmin()

    userLifecycle.updateUser(user.copy(enabled = body.enabled))
    if (!body.enabled) expireAllSessions(user)

    return findUser(id).toAdminDto(detailed = true)
  }

  @GetMapping("{id}/sessions")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "List the active sessions of a user")
  fun sessions(
    @PathVariable id: String,
  ): List<UserSessionDto> {
    val user = findUser(id)
    return sessionRegistry
      .getAllSessions(LantaiPrincipal(user), false)
      .map { UserSessionDto(it.sessionId, it.lastRequest.toInstant(), it.isExpired) }
      .sortedByDescending { it.lastRequest }
  }

  @PostMapping("{id}/sessions/expire")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Expire one or all sessions of a user")
  fun expireSessions(
    @PathVariable id: String,
    @RequestBody(required = false) body: SessionExpireDto?,
  ) {
    val user = findUser(id)
    val sessionId = body?.sessionId?.takeIf { it.isNotBlank() }

    if (sessionId == null) {
      expireAllSessions(user)
      return
    }

    val owned = sessionRegistry.getAllSessions(LantaiPrincipal(user), true).any { it.sessionId == sessionId }
    if (!owned) throw ResponseStatusException(HttpStatus.NOT_FOUND, "Session does not belong to this user")
    sessionRepository.deleteById(sessionId)
  }

  @GetMapping("{id}/api-keys")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "List the API keys of a user")
  fun apiKeys(
    @PathVariable id: String,
  ): Collection<ApiKeyDto> {
    findUser(id)
    return userRepository.findApiKeyByUserId(id).map { it.toDto().redacted() }
  }

  @DeleteMapping("{id}/api-keys/{keyId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Delete an API key of a user")
  fun deleteApiKey(
    @PathVariable id: String,
    @PathVariable keyId: String,
  ) {
    findUser(id)
    if (!userRepository.existsApiKeyByIdAndUserId(keyId, id)) throw ResponseStatusException(HttpStatus.NOT_FOUND)
    userRepository.deleteApiKeyByIdAndUserId(keyId, id)
  }

  @PostMapping("{id}/password/reset")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Reset the password of a user and expire its sessions")
  fun resetPassword(
    @PathVariable id: String,
    @AuthenticationPrincipal principal: LantaiPrincipal,
  ): PasswordResetDto {
    requireOtherUser(principal, id)
    val user = findUser(id)
    val password = RandomStringUtils.secure().nextAlphanumeric(PASSWORD_LENGTH)
    userLifecycle.updatePassword(user, password, true)
    return PasswordResetDto(user.username, user.email, password)
  }

  private fun findUser(id: String): LantaiUser =
    userRepository.findByIdOrNull(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

  private fun requireOtherUser(
    principal: LantaiPrincipal,
    id: String,
  ) {
    if (principal.user.id == id) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "This action cannot target your own account")
  }

  private fun requireAnotherAdmin() {
    val admins = userRepository.findAll().count { it.isAdmin }
    if (admins <= 1) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one administrator is required")
  }

  private fun expireAllSessions(user: LantaiUser) {
    sessionRegistry.getAllSessions(LantaiPrincipal(user), false).forEach { it.expireNow() }
  }

  private fun parseRoles(roles: Iterable<String>): Set<UserRoles> =
    roles
      .mapNotNull {
        val upper = it.trim().uppercase()
        if (upper == "USER") return@mapNotNull null
        runCatching { UserRoles.valueOf(upper) }.getOrNull()
          ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown role: $it")
      }.toSet()

  private fun LantaiUser.toAdminDto(detailed: Boolean = false): AdminUserDto {
    val sessions = if (detailed) runCatching { sessionRegistry.getAllSessions(LantaiPrincipal(this), false) }.getOrDefault(emptyList()) else null
    return AdminUserDto(
      id = id,
      username = username,
      email = email,
      roles = roles.map { it.name }.toSet() + "USER",
      enabled = enabled,
      sharedAllLibraries = sharedAllLibraries,
      sharedLibrariesIds = sharedLibrariesIds,
      labelsAllow = restrictions.labelsAllow,
      labelsExclude = restrictions.labelsExclude,
      ageRestriction = restrictions.ageRestriction?.let { AgeRestrictionDto(it.age, it.restriction) },
      createdDate = createdDate,
      lastModifiedDate = lastModifiedDate,
      lastActivity = if (detailed) authenticationActivityRepository.findMostRecentByUser(this, null)?.toDto() else null,
      activeSessions = sessions?.count { !it.isExpired },
      apiKeyCount = if (detailed) userRepository.findApiKeyByUserId(id).size else null,
    )
  }

  private companion object {
    const val PASSWORD_LENGTH = 24
    val DEFAULT_ROLES = setOf(UserRoles.FILE_DOWNLOAD, UserRoles.PAGE_STREAMING)

    val ROLE_DESCRIPTIONS =
      mapOf(
        UserRoles.ADMIN to "Full administrative access",
        UserRoles.FILE_DOWNLOAD to "Download book files",
        UserRoles.PAGE_STREAMING to "Stream book pages",
        UserRoles.KOBO_SYNC to "Sync with Kobo devices",
        UserRoles.KOREADER_SYNC to "Sync with KOReader",
      )
  }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class AdminUserDto(
  val id: String,
  val username: String,
  val email: String?,
  val roles: Set<String>,
  val enabled: Boolean,
  val sharedAllLibraries: Boolean,
  val sharedLibrariesIds: Set<String>,
  val labelsAllow: Set<String>,
  val labelsExclude: Set<String>,
  val ageRestriction: AgeRestrictionDto? = null,
  val createdDate: LocalDateTime,
  val lastModifiedDate: LocalDateTime,
  val lastActivity: AuthenticationActivityDto? = null,
  val activeSessions: Int? = null,
  val apiKeyCount: Int? = null,
)

data class ProfileUpdateDto(
  val username: String = "",
  val email: String? = null,
)

data class RoleInfoDto(
  val name: String,
  val description: String,
  val default: Boolean,
)

data class RolesUpdateDto(
  val roles: Set<String> = emptySet(),
)

data class UserStatusDto(
  val enabled: Boolean,
)

data class SessionExpireDto(
  val sessionId: String? = null,
)

data class UserSessionDto(
  val sessionId: String,
  val lastRequest: Instant,
  val expired: Boolean,
)

data class PasswordResetDto(
  val username: String,
  val email: String?,
  val password: String,
)
