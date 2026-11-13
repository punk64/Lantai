package org.lantai.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.lantai.domain.model.PluginKvEntry
import org.lantai.infrastructure.plugin.PluginCredentialService
import org.lantai.infrastructure.plugin.PluginCredentialSummary
import org.lantai.infrastructure.plugin.PluginEventBroker
import org.lantai.infrastructure.plugin.PluginKvService
import org.lantai.infrastructure.plugin.PluginNotFoundException
import org.lantai.infrastructure.plugin.PluginQuotaException
import org.lantai.infrastructure.plugin.PluginRequestException
import org.lantai.infrastructure.plugin.PluginSubscriptionException
import org.lantai.infrastructure.plugin.PluginTokenService
import org.lantai.infrastructure.security.LantaiPrincipal
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.LocalDateTime

@RestController
@RequestMapping("api/lantai/v1/plugin")
@Tag(name = "Lantai Plugin Runtime")
class LantaiPluginRuntimeController(
  private val kv: PluginKvService,
  private val broker: PluginEventBroker,
  private val credentials: PluginCredentialService,
) {
  @GetMapping("kv", produces = [MediaType.APPLICATION_JSON_VALUE])
  @Operation(summary = "List the caller plugin key-value keys, paged")
  fun list(
    @RequestParam(name = "page", defaultValue = "0") page: Int,
    @RequestParam(name = "size", defaultValue = "100") size: Int,
    @AuthenticationPrincipal principal: LantaiPrincipal?,
  ): PluginKvListDto {
    val pluginId = requirePluginId(principal)
    val p = page.coerceAtLeast(0)
    val s = size.coerceIn(1, PluginKvService.MAX_PAGE_SIZE)
    val items = translate { kv.list(pluginId, p, s) }
    val total = translate { kv.count(pluginId) }
    return PluginKvListDto(
      usage = translate { kv.usage(pluginId) },
      limit = PluginKvService.MAX_TOTAL_BYTES,
      total = total,
      page = p,
      size = s,
      totalPages = maxOf(1, (total + s - 1) / s),
      items = items.map { it.toDto() },
    )
  }

  @GetMapping("kv/{key}", produces = [MediaType.APPLICATION_JSON_VALUE])
  @Operation(summary = "Read one key of the caller plugin")
  fun read(
    @PathVariable key: String,
    @AuthenticationPrincipal principal: LantaiPrincipal?,
  ): PluginKvItemDto {
    val pluginId = requirePluginId(principal)
    val stored = translate { kv.get(pluginId, key) } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "键不存在")
    return PluginKvItemDto(
      key = stored.key,
      value = stored.value,
      size = stored.value.toByteArray(Charsets.UTF_8).size.toLong(),
      lastModifiedDate = stored.lastModifiedDate,
    )
  }

  @PutMapping("kv/{key}", produces = [MediaType.APPLICATION_JSON_VALUE])
  @Operation(summary = "Write one key of the caller plugin")
  fun write(
    @PathVariable key: String,
    @RequestBody body: PluginKvWriteDto,
    @AuthenticationPrincipal principal: LantaiPrincipal?,
  ): PluginKvEntryDto {
    val pluginId = requirePluginId(principal)
    return translate { kv.put(pluginId, key, body.value) }.toDto()
  }

  @DeleteMapping("kv/{key}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Delete one key of the caller plugin")
  fun delete(
    @PathVariable key: String,
    @AuthenticationPrincipal principal: LantaiPrincipal?,
  ) {
    val pluginId = requirePluginId(principal)
    translate { kv.delete(pluginId, key) }
  }

  @GetMapping("events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
  @Operation(summary = "Stream domain events to the caller plugin")
  fun events(
    @AuthenticationPrincipal principal: LantaiPrincipal?,
    @RequestHeader(name = "Last-Event-ID", required = false) lastEventId: String?,
  ): SseEmitter = translate { broker.open(requirePluginId(principal), lastEventId?.trim()?.toLongOrNull()) }

  @GetMapping("credentials", produces = [MediaType.APPLICATION_JSON_VALUE])
  @Operation(summary = "List the caller plugin credentials, values excluded")
  fun credentials(@AuthenticationPrincipal principal: LantaiPrincipal?): List<PluginCredentialSummary> =
    translate { credentials.list(requirePluginId(principal)) }

  @GetMapping("credentials/{name}", produces = [MediaType.APPLICATION_JSON_VALUE])
  @Operation(summary = "Read one credential of the caller plugin")
  fun readCredential(
    @PathVariable name: String,
    @AuthenticationPrincipal principal: LantaiPrincipal?,
  ): PluginCredentialValueDto {
    val pluginId = requirePluginId(principal)
    val value = translate { credentials.get(pluginId, name) } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "凭据不存在")
    return PluginCredentialValueDto(name = name, value = value)
  }

  @PutMapping("credentials/{name}", produces = [MediaType.APPLICATION_JSON_VALUE])
  @Operation(summary = "Write one credential of the caller plugin")
  fun writeCredential(
    @PathVariable name: String,
    @RequestBody body: PluginCredentialWriteDto,
    @AuthenticationPrincipal principal: LantaiPrincipal?,
  ) {
    translate { credentials.put(requirePluginId(principal), name, body.value) }
  }

  @DeleteMapping("credentials/{name}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Delete one credential of the caller plugin")
  fun deleteCredential(
    @PathVariable name: String,
    @AuthenticationPrincipal principal: LantaiPrincipal?,
  ) {
    translate { credentials.delete(requirePluginId(principal), name) }
  }

  private fun requirePluginId(principal: LantaiPrincipal?): String {
    val name = principal?.name ?: ""
    if (!name.startsWith(PluginTokenService.TOKEN_NAME_PREFIX)) {
      throw ResponseStatusException(HttpStatus.FORBIDDEN, "该接口仅限插件令牌访问")
    }
    return name.removePrefix(PluginTokenService.TOKEN_NAME_PREFIX)
  }

  private fun <T> translate(block: () -> T): T =
    try {
      block()
    } catch (ex: PluginNotFoundException) {
      throw ResponseStatusException(HttpStatus.NOT_FOUND, ex.message)
    } catch (ex: PluginQuotaException) {
      throw ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, ex.message)
    } catch (ex: PluginSubscriptionException) {
      throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, ex.message)
    } catch (ex: PluginRequestException) {
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, ex.message)
    }

  private fun PluginKvEntry.toDto(): PluginKvEntryDto = PluginKvEntryDto(key = key, size = size, lastModifiedDate = lastModifiedDate)
}

data class PluginKvWriteDto(
  val value: String = "",
)

data class PluginCredentialWriteDto(
  val value: String = "",
)

data class PluginCredentialValueDto(
  val name: String,
  val value: String,
)

data class PluginKvEntryDto(
  val key: String,
  val size: Long,
  val lastModifiedDate: LocalDateTime?,
)

data class PluginKvItemDto(
  val key: String,
  val value: String,
  val size: Long,
  val lastModifiedDate: LocalDateTime?,
)

data class PluginKvListDto(
  val usage: Long,
  val limit: Long,
  val total: Int,
  val page: Int,
  val size: Int,
  val totalPages: Int,
  val items: List<PluginKvEntryDto>,
)
