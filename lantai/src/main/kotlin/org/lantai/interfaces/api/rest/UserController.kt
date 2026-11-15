package org.lantai.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.lantai.application.query.UnpagedSorted
import org.lantai.domain.model.AgeRestriction
import org.lantai.domain.model.ContentRestrictions
import org.lantai.domain.model.DuplicateNameException
import org.lantai.domain.model.LantaiUser
import org.lantai.domain.model.UserEmailAlreadyExistsException
import org.lantai.domain.model.UserRoles
import org.lantai.domain.persistence.AuthenticationActivityRepository
import org.lantai.domain.persistence.LantaiUserRepository
import org.lantai.domain.persistence.LibraryRepository
import org.lantai.domain.service.LantaiUserLifecycle
import org.lantai.infrastructure.openapi.OpenApiConfiguration.TagNames
import org.lantai.infrastructure.security.LantaiPrincipal
import org.lantai.interfaces.api.rest.dto.AllowExcludeDto
import org.lantai.interfaces.api.rest.dto.ApiKeyDto
import org.lantai.interfaces.api.rest.dto.ApiKeyRequestDto
import org.lantai.interfaces.api.rest.dto.AuthenticationActivityDto
import org.lantai.interfaces.api.rest.dto.PasswordUpdateDto
import org.lantai.interfaces.api.rest.dto.UserCreationDto
import org.lantai.interfaces.api.rest.dto.UserDto
import org.lantai.interfaces.api.rest.dto.UserUpdateDto
import org.lantai.interfaces.api.rest.dto.redacted
import org.lantai.interfaces.api.rest.dto.toDto
import org.springdoc.core.converters.models.PageableAsQueryParam
import org.springframework.core.env.Environment
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("api/v1/users", "api/v2/users", produces = [MediaType.APPLICATION_JSON_VALUE])
class UserController(
  private val userLifecycle: LantaiUserLifecycle,
  private val userRepository: LantaiUserRepository,
  private val libraryRepository: LibraryRepository,
  private val authenticationActivityRepository: AuthenticationActivityRepository,
  env: Environment,
) {
  private val demo = env.activeProfiles.contains("demo")

  @GetMapping("me")
  @Operation(summary = "Retrieve current user", tags = [TagNames.CURRENT_USER])
  fun getCurrentUser(
    @AuthenticationPrincipal principal: LantaiPrincipal,
    @RequestParam(name = "remember-me", required = false) rememberMe: Boolean?,
  ): UserDto = principal.toDto()

  @PatchMapping("me/password")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Update current user's password", tags = [TagNames.CURRENT_USER])
  fun updatePasswordForCurrentUser(
    @AuthenticationPrincipal principal: LantaiPrincipal,
    @Valid @RequestBody
    newPasswordDto: PasswordUpdateDto,
    request: HttpServletRequest,
  ) {
    if (demo) throw ResponseStatusException(HttpStatus.FORBIDDEN)
    val keepSessionId = runCatching { request.getSession(false)?.id }.getOrNull()
    userRepository.findByIdOrNull(principal.user.id)?.let { user ->
      userLifecycle.updatePassword(user, newPasswordDto.password, true, keepSessionId)
    } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
  }

  @GetMapping
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "List users", tags = [TagNames.USERS])
  fun getUsers(): List<UserDto> = userRepository.findAll().map { it.toDto() }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Create user", tags = [TagNames.USERS])
  fun addUser(
    @Valid @RequestBody
    newUser: UserCreationDto,
  ): UserDto {
    val login = newUser.username?.trim()?.takeIf { it.isNotEmpty() }
    val mail = newUser.email?.trim()?.takeIf { it.isNotEmpty() }
    if (login == null && mail == null)
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, "A username or an email is required")
    return try {
      userLifecycle
        .createUser(
          with(newUser) {
            LantaiUser(
              email = mail,
              password = password,
              roles = UserRoles.Companion.valuesOf(roles),
              // keep existing behaviour before those properties were added, by default new user has access to all libraries
              sharedAllLibraries = sharedLibraries == null || sharedLibraries.all,
              sharedLibrariesIds =
                if (sharedLibraries == null || sharedLibraries.all)
                  emptySet()
                else
                  libraryRepository.findAllByIds(sharedLibraries.libraryIds).map { it.id }.toSet(),
              // keep existing behaviour before those properties were added, by default no restrictions are applied
              restrictions =
                ContentRestrictions(
                  ageRestriction =
                    ageRestriction.let {
                      if (it == null || it.restriction == AllowExcludeDto.NONE)
                        null
                      else
                        AgeRestriction(it.age, it.restriction.toDomain())
                    },
                  labelsAllow = labelsAllow ?: emptySet(),
                  labelsExclude = labelsExclude ?: emptySet(),
                ),
              username = login ?: mail!!.substringBefore('@'),
            )
          },
        ).toDto()
    } catch (_: UserEmailAlreadyExistsException) {
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, "A user with this username or email already exists")
    }
  }

  @DeleteMapping("{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('ADMIN') and #principal.user.id != #id")
  @Operation(summary = "Delete user", tags = [TagNames.USERS])
  fun deleteUserById(
    @PathVariable id: String,
    @AuthenticationPrincipal principal: LantaiPrincipal,
  ) {
    userRepository.findByIdOrNull(id)?.let {
      userLifecycle.deleteUser(it)
    } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
  }

  @PatchMapping("{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('ADMIN') and #principal.user.id != #id")
  @Operation(summary = "Update user", tags = [TagNames.USERS])
  fun updateUserById(
    @PathVariable id: String,
    @Valid @RequestBody
    patch: UserUpdateDto,
    @AuthenticationPrincipal principal: LantaiPrincipal,
  ) {
    userRepository.findByIdOrNull(id)?.let { existing ->
      val updatedUser =
        with(patch) {
          existing.copy(
            roles = if (isSet("roles")) UserRoles.valuesOf(roles!!) else existing.roles,
            sharedAllLibraries = if (isSet("sharedLibraries")) sharedLibraries!!.all else existing.sharedAllLibraries,
            sharedLibrariesIds =
              if (isSet("sharedLibraries")) {
                if (sharedLibraries!!.all)
                  emptySet()
                else
                  libraryRepository.findAllByIds(sharedLibraries!!.libraryIds).map { it.id }.toSet()
              } else {
                existing.sharedLibrariesIds
              },
            restrictions =
              ContentRestrictions(
                ageRestriction =
                  if (isSet("ageRestriction")) {
                    if (ageRestriction == null || ageRestriction?.restriction == AllowExcludeDto.NONE)
                      null
                    else
                      AgeRestriction(ageRestriction!!.age, ageRestriction!!.restriction.toDomain())
                  } else {
                    existing.restrictions.ageRestriction
                  },
                labelsAllow =
                  if (isSet("labelsAllow"))
                    labelsAllow
                      ?: emptySet()
                  else
                    existing.restrictions.labelsAllow,
                labelsExclude =
                  if (isSet("labelsExclude"))
                    labelsExclude
                      ?: emptySet()
                  else
                    existing.restrictions.labelsExclude,
              ),
          )
        }
      userLifecycle.updateUser(updatedUser)
    } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
  }

  @PatchMapping("{id}/password")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('ADMIN') or #principal.user.id == #id")
  @Operation(summary = "Update user's password", tags = [TagNames.USERS])
  fun updatePasswordByUserId(
    @PathVariable id: String,
    @AuthenticationPrincipal principal: LantaiPrincipal,
    @Valid @RequestBody
    newPasswordDto: PasswordUpdateDto,
  ) {
    if (demo) throw ResponseStatusException(HttpStatus.FORBIDDEN)
    userRepository.findByIdOrNull(id)?.let { user ->
      userLifecycle.updatePassword(user, newPasswordDto.password, user.id != principal.user.id)
    } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
  }

  @GetMapping("me/authentication-activity")
  @PageableAsQueryParam
  @Operation(summary = "Retrieve authentication activity for the current user", tags = [TagNames.CURRENT_USER])
  fun getAuthenticationActivityForCurrentUser(
    @AuthenticationPrincipal principal: LantaiPrincipal,
    @RequestParam(name = "unpaged", required = false) unpaged: Boolean = false,
    @Parameter(hidden = true) page: Pageable,
  ): Page<AuthenticationActivityDto> {
    if (demo && !principal.user.isAdmin) throw ResponseStatusException(HttpStatus.FORBIDDEN)
    val sort =
      if (page.sort.isSorted)
        page.sort
      else
        Sort.by(Sort.Order.desc("dateTime"))

    val pageRequest =
      if (unpaged)
        UnpagedSorted(sort)
      else
        PageRequest.of(
          page.pageNumber,
          page.pageSize,
          sort,
        )

    return authenticationActivityRepository.findAllByUser(principal.user, pageRequest).map { it.toDto() }
  }

  @GetMapping("authentication-activity")
  @PageableAsQueryParam
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Retrieve authentication activity", tags = [TagNames.USERS])
  fun getAuthenticationActivity(
    @RequestParam(name = "unpaged", required = false) unpaged: Boolean = false,
    @Parameter(hidden = true) page: Pageable,
  ): Page<AuthenticationActivityDto> {
    val sort =
      if (page.sort.isSorted)
        page.sort
      else
        Sort.by(Sort.Order.desc("dateTime"))

    val pageRequest =
      if (unpaged)
        UnpagedSorted(sort)
      else
        PageRequest.of(
          page.pageNumber,
          page.pageSize,
          sort,
        )

    return authenticationActivityRepository.findAll(pageRequest).map { it.toDto() }
  }

  @GetMapping("{id}/authentication-activity/latest")
  @PreAuthorize("hasRole('ADMIN') or #principal.user.id == #id")
  @Operation(summary = "Retrieve latest authentication activity for a user", tags = [TagNames.USERS])
  fun getLatestAuthenticationActivityByUserId(
    @PathVariable id: String,
    @AuthenticationPrincipal principal: LantaiPrincipal,
    @RequestParam(required = false, name = "apikey_id") apiKeyId: String?,
  ): AuthenticationActivityDto =
    userRepository.findByIdOrNull(id)?.let { user ->
      authenticationActivityRepository.findMostRecentByUser(user, apiKeyId)?.toDto()
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

  @GetMapping("me/api-keys")
  @Operation(summary = "Retrieve API keys", tags = [TagNames.API_KEYS])
  fun getApiKeysForCurrentUser(
    @AuthenticationPrincipal principal: LantaiPrincipal,
  ): Collection<ApiKeyDto> {
    if (demo && !principal.user.isAdmin) throw ResponseStatusException(HttpStatus.FORBIDDEN)
    return userRepository.findApiKeyByUserId(principal.user.id).map { it.toDto().redacted() }
  }

  @PostMapping("me/api-keys")
  @Operation(summary = "Create API key", tags = [TagNames.API_KEYS])
  fun createApiKeyForCurrentUser(
    @AuthenticationPrincipal principal: LantaiPrincipal,
    @Valid @RequestBody apiKeyRequest: ApiKeyRequestDto,
  ): ApiKeyDto {
    if (demo && !principal.user.isAdmin) throw ResponseStatusException(HttpStatus.FORBIDDEN)
    return try {
      userLifecycle.createApiKey(principal.user, apiKeyRequest.comment)?.toDto()
    } catch (e: DuplicateNameException) {
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.code)
    }
      ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Failed to generate API key")
  }

  @DeleteMapping("me/api-keys/{keyId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Delete API key", tags = [TagNames.API_KEYS])
  fun deleteApiKeyByKeyId(
    @AuthenticationPrincipal principal: LantaiPrincipal,
    @PathVariable keyId: String,
  ) {
    if (!userRepository.existsApiKeyByIdAndUserId(keyId, principal.user.id))
      throw ResponseStatusException(HttpStatus.NOT_FOUND)
    userRepository.deleteApiKeyByIdAndUserId(keyId, principal.user.id)
  }
}
