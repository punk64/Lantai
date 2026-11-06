package org.lantai.interfaces.api.rest

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.lantai.domain.model.ApiToken
import org.lantai.domain.persistence.ApiTokenRepository
import org.lantai.infrastructure.metadata.scrape.ScrapeAgentDetail
import org.lantai.infrastructure.metadata.scrape.ScrapeAgentService
import org.lantai.infrastructure.metadata.scrape.ScrapeAgentStats
import org.lantai.infrastructure.metadata.scrape.ScrapeAgentSubmitResult
import org.lantai.infrastructure.metadata.scrape.ScrapeException
import org.lantai.infrastructure.security.LantaiPrincipal
import org.lantai.infrastructure.security.apitoken.ApiTokenGenerator
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("api/lantai/v1/scrape/agent", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Lantai Scrape Agent")
class LantaiScrapeAgentController(
  private val agentService: ScrapeAgentService,
  private val apiTokenRepository: ApiTokenRepository,
  private val apiTokenGenerator: ApiTokenGenerator,
  private val objectMapper: ObjectMapper,
) {
  @GetMapping("queue")
  @PreAuthorize("hasAnyRole('ADMIN', 'SCRAPE_READ')")
  @Operation(summary = "Read one page of the scrape queue for an external agent")
  fun page(
    @RequestParam(name = "offset", defaultValue = "0") offset: Int,
    @RequestParam(name = "limit", defaultValue = "20") limit: Int,
  ): Map<String, Any> = agentService.page(offset, limit)

  @GetMapping("queue/{id}")
  @PreAuthorize("hasAnyRole('ADMIN', 'SCRAPE_READ')")
  @Operation(summary = "Read one queue entry with the hints an external agent needs")
  fun detail(
    @PathVariable id: String,
  ): ScrapeAgentDetail =
    try {
      agentService.detail(id)
    } catch (e: NoSuchElementException) {
      throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message, e)
    }

  @PostMapping("queue/{id}/result")
  @PreAuthorize("hasAnyRole('ADMIN', 'SCRAPE_SUBMIT')")
  @Operation(summary = "Submit a scrape result for a queue entry, or pass a series id to overwrite an already applied result")
  fun submit(
    @PathVariable id: String,
    @RequestBody payload: JsonNode,
    @AuthenticationPrincipal principal: LantaiPrincipal,
  ): ScrapeAgentSubmitResult =
    try {
      agentService.submit(id, objectMapper.writeValueAsString(payload), principal.apiKey?.id, principal.username)
    } catch (e: NoSuchElementException) {
      throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message, e)
    } catch (e: ScrapeException) {
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
    }

  @GetMapping("stats")
  @PreAuthorize("hasAnyRole('ADMIN', 'SCRAPE_READ')")
  @Operation(summary = "Counters for the scrape queue and the inbox")
  fun stats(): ScrapeAgentStats = agentService.stats()

  @GetMapping("token")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "List the scrape only API tokens")
  fun tokens(): List<ScrapeAgentTokenDto> =
    apiTokenRepository.findAll().filter { it.scopes.isNotEmpty() && it.scopes.all { scope -> scope.isScrapeOnly } }.map { it.toDto() }

  @PostMapping("token")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Create a scrape only API token with fixed scopes")
  fun createToken(
    @RequestBody body: ScrapeAgentTokenRequest,
    @AuthenticationPrincipal principal: LantaiPrincipal,
  ): ScrapeAgentTokenDto {
    val name = body.name?.trim()?.takeIf { it.isNotEmpty() } ?: "scrape-agent-${System.currentTimeMillis().toString(36)}"
    if (apiTokenRepository.existsByName(name)) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "令牌名称已存在")
    if (body.rotate) {
      apiTokenRepository.findAll()
        .filter { it.scopes.isNotEmpty() && it.scopes.all { scope -> scope.isScrapeOnly } }
        .forEach { apiTokenRepository.deleteById(it.id) }
    }
    val expires =
      when {
        body.expiresDate != null -> body.expiresDate
        body.expiresInDays != null ->
          if (body.expiresInDays <= 0) null
          else java.time.LocalDateTime.now().plusDays(body.expiresInDays.coerceAtMost(3650).toLong())
        else -> java.time.LocalDateTime.now().plusDays(7)
      }
    val token =
      ApiToken(
        name = name,
        ownerId = principal.user.id,
        tokenValue = apiTokenGenerator.generate(),
        scopes = setOf(ApiToken.Scope.SCRAPE_READ, ApiToken.Scope.SCRAPE_SUBMIT),
        expiresDate = expires,
      )
    apiTokenRepository.insert(token)
    return token.toDto(token.tokenValue)
  }

  @DeleteMapping("token/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Revoke a scrape only API token")
  fun deleteToken(
    @PathVariable id: String,
  ) {
    val token = apiTokenRepository.findByIdOrNull(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    if (token.scopes.isEmpty() || !token.scopes.all { it.isScrapeOnly }) {
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, "只能删除刮削专用令牌")
    }
    apiTokenRepository.deleteById(id)
  }

  @GetMapping("token/{id}/value")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Read the plain value of a scrape only API token")
  fun tokenValue(
    @PathVariable id: String,
  ): ScrapeAgentTokenValueDto {
    val token = apiTokenRepository.findByIdOrNull(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    if (token.scopes.isEmpty() || !token.scopes.all { scope -> scope.isScrapeOnly }) {
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, "只能查看刮削专用令牌")
    }
    if (token.tokenValue.startsWith(HASH_LEGACY_PREFIX)) {
      throw ResponseStatusException(HttpStatus.CONFLICT, "该令牌以旧版方式存储，明文无法恢复，请重新生成")
    }
    return ScrapeAgentTokenValueDto(token.tokenValue)
  }

  private companion object {
    private const val HASH_LEGACY_PREFIX = "sha512:"
  }

  private fun maskValue(raw: String): String =
    if (raw.length <= 12) "****" else raw.take(10) + "…" + raw.takeLast(4)

  private fun ApiToken.toDto(plainValue: String? = null) =
    ScrapeAgentTokenDto(
      id = id,
      name = name,
      value = plainValue ?: if (tokenValue.startsWith(HASH_LEGACY_PREFIX)) "****" else maskValue(tokenValue),
      enabled = enabled,
      expiresDate = expiresDate?.toString(),
      lastUsedDate = lastUsedDate?.toString(),
      useCount = useCount,
      createdDate = createdDate.toString(),
      scopes = scopes.map { it.label }.sorted(),
      viewable = !tokenValue.startsWith(HASH_LEGACY_PREFIX),
    )
}

data class ScrapeAgentTokenValueDto(
  val value: String,
)


data class ScrapeAgentTokenDto(
  val id: String,
  val name: String,
  val value: String,
  val enabled: Boolean,
  val expiresDate: String?,
  val lastUsedDate: String?,
  val useCount: Int,
  val createdDate: String,
  val scopes: List<String>,
  val viewable: Boolean,
)

data class ScrapeAgentTokenRequest(
  val name: String? = null,
  val rotate: Boolean = false,
  val expiresDate: java.time.LocalDateTime? = null,
  val expiresInDays: Int? = null,
)
