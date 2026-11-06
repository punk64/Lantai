package org.lantai.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import org.lantai.domain.model.ApiToken
import org.lantai.domain.persistence.ApiTokenRepository
import org.lantai.domain.persistence.LantaiUserRepository
import org.lantai.infrastructure.security.LantaiPrincipal
import org.lantai.infrastructure.security.apitoken.ApiTokenGenerator
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
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime

@RestController
@RequestMapping("api/lantai/v1/api-tokens", produces = [MediaType.APPLICATION_JSON_VALUE])
@PreAuthorize("hasRole('ADMIN')")
class LantaiApiTokenController(
  private val apiTokenRepository: ApiTokenRepository,
  private val userRepository: LantaiUserRepository,
  private val apiTokenGenerator: ApiTokenGenerator,
) {
  @GetMapping
  @Operation(summary = "List API tokens")
  fun list(): List<ApiTokenDto> = apiTokenRepository.findAll().map { it.toDto() }

  @GetMapping("scopes")
  @Operation(summary = "List available API token scopes")
  fun scopes(): List<ApiTokenScopeDto> =
    ApiToken.Scope.entries.filter { !it.isScrapeOnly }.map { ApiTokenScopeDto(it.label, it.display) }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Create an API token")
  fun create(
    @RequestBody body: ApiTokenCreateDto,
    @AuthenticationPrincipal principal: LantaiPrincipal,
  ): ApiTokenDto {
    val name = requireName(body.name)
    if (apiTokenRepository.existsByName(name)) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "令牌名称已存在")
    val scopes = resolveScopes(body.scopes)
    if (scopes.isEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "至少选择一个权限范围")
    val expiresDate = body.expiresDate?.also { requireFuture(it) }
    val token =
      ApiToken(
        name = name,
        ownerId = principal.user.id,
        tokenValue = apiTokenGenerator.generate(),
        scopes = scopes,
        expiresDate = expiresDate,
      )
    apiTokenRepository.insert(token)
    return token.toDto(token.tokenValue)
  }

  @PatchMapping("{id}")
  @Operation(summary = "Update an API token")
  fun update(
    @PathVariable id: String,
    @RequestBody body: ApiTokenUpdateDto,
  ): ApiTokenDto {
    val token = apiTokenRepository.findByIdOrNull(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    val name = body.name?.let { requireName(it) } ?: token.name
    if (apiTokenRepository.existsByName(name, id)) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "令牌名称已存在")
    val scopes =
      body.scopes?.let {
        resolveScopes(it).also { resolved ->
          if (resolved.isEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "至少选择一个权限范围")
        }
      } ?: token.scopes
    val expiresDate =
      when {
        body.clearExpiry -> null
        body.expiresDate != null -> body.expiresDate.also { requireFuture(it) }
        else -> token.expiresDate
      }
    val updated =
      token.copy(
        name = name,
        scopes = scopes,
        expiresDate = expiresDate,
        enabled = body.enabled ?: token.enabled,
        lastModifiedDate = LocalDateTime.now(),
      )
    apiTokenRepository.update(updated)
    return apiTokenRepository.findByIdOrNull(id)?.toDto() ?: updated.toDto()
  }

  @DeleteMapping("{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Delete an API token")
  fun delete(
    @PathVariable id: String,
  ) {
    if (apiTokenRepository.findByIdOrNull(id) == null) throw ResponseStatusException(HttpStatus.NOT_FOUND)
    apiTokenRepository.deleteById(id)
  }

  @GetMapping("{id}/value")
  @Operation(summary = "Read the plain value of an API token")
  fun value(
    @PathVariable id: String,
  ): ApiTokenValueDto {
    val token = apiTokenRepository.findByIdOrNull(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    if (token.tokenValue.startsWith(HASH_LEGACY_PREFIX)) {
      throw ResponseStatusException(HttpStatus.CONFLICT, "该令牌以旧版方式存储，明文无法恢复，请重新生成")
    }
    return ApiTokenValueDto(token.tokenValue)
  }

  private fun requireName(raw: String): String {
    val name = raw.trim()
    if (name.isEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "令牌名称不能为空")
    if (name.length > 128) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "令牌名称不能超过 128 个字符")
    return name
  }

  private fun requireFuture(date: LocalDateTime) {
    if (!date.isAfter(LocalDateTime.now())) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "有效期必须晚于当前时间")
  }

  private fun resolveScopes(values: List<String>): Set<ApiToken.Scope> {
    if (values.isEmpty()) return emptySet()
    val parsed = ApiToken.Scope.parse(values)
    if (parsed.isEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "权限范围无法识别")
    return parsed
  }

  private fun maskValue(raw: String): String =
    if (raw.length <= 12) "****" else raw.take(10) + "…" + raw.takeLast(4)

  private fun ApiToken.toDto(plainValue: String? = null): ApiTokenDto {
    val owner = userRepository.findByIdOrNull(ownerId)
    return ApiTokenDto(
      id = id,
      name = name,
      value = plainValue ?: if (tokenValue.startsWith(HASH_LEGACY_PREFIX)) "****" else maskValue(tokenValue),
      scopes = scopes.map { it.label }.sorted(),
      enabled = enabled,
      expiresDate = expiresDate,
      lastUsedDate = lastUsedDate,
      lastUsedIp = lastUsedIp,
      useCount = useCount,
      createdDate = createdDate,
      ownerId = ownerId,
      ownerUsername = owner?.username ?: ownerId,
      expired = isExpired(),
      viewable = !tokenValue.startsWith(HASH_LEGACY_PREFIX),
    )
  }

  private companion object {
    private const val HASH_LEGACY_PREFIX = "sha512:"
  }
}

data class ApiTokenValueDto(
  val value: String,
)

data class ApiTokenDto(
  val id: String,
  val name: String,
  val value: String,
  val scopes: List<String>,
  val enabled: Boolean,
  val expiresDate: LocalDateTime?,
  val lastUsedDate: LocalDateTime?,
  val lastUsedIp: String?,
  val useCount: Int,
  val createdDate: LocalDateTime,
  val ownerId: String,
  val ownerUsername: String,
  val expired: Boolean,
  val viewable: Boolean,
)

data class ApiTokenScopeDto(
  val name: String,
  val display: String,
)

data class ApiTokenCreateDto(
  val name: String,
  val scopes: List<String> = emptyList(),
  val expiresDate: LocalDateTime? = null,
)

data class ApiTokenUpdateDto(
  val name: String? = null,
  val scopes: List<String>? = null,
  val expiresDate: LocalDateTime? = null,
  val clearExpiry: Boolean = false,
  val enabled: Boolean? = null,
)
