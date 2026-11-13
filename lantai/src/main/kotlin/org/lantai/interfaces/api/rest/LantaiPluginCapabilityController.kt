package org.lantai.interfaces.api.rest

import com.fasterxml.jackson.databind.ObjectMapper
import io.swagger.v3.oas.annotations.Operation
import org.lantai.infrastructure.plugin.CapabilityProvider
import org.lantai.infrastructure.plugin.PluginCapabilityInvoker
import org.lantai.infrastructure.plugin.PluginNotFoundException
import org.lantai.infrastructure.plugin.PluginRequestException
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("api/lantai/v1/capabilities")
@PreAuthorize("isAuthenticated()")
class LantaiPluginCapabilityController(
  private val invoker: PluginCapabilityInvoker,
  private val mapper: ObjectMapper,
) {
  @GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
  @Operation(summary = "List provider capabilities and their default plugin")
  fun list(): List<CapabilityProvider> = invoker.providers()

  @PostMapping("tts.speak")
  @Operation(summary = "Synthesize speech through the default tts.speak plugin")
  fun speak(
    @RequestBody body: TtsSpeakDto,
  ): ResponseEntity<ByteArray> {
    val text = body.text.trim()
    if (text.isEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "朗读文本不能为空")
    if (text.length > MAX_TTS_CHARS) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "朗读文本不得超过 $MAX_TTS_CHARS 字")
    val payload = mapper.createObjectNode().put("text", text)
    body.voice?.trim()?.takeIf { it.isNotEmpty() }?.let { payload.put("voice", it) }
    body.rate?.let { payload.put("rate", it.coerceIn(MIN_RATE, MAX_RATE)) }
    body.format?.trim()?.takeIf { it.isNotEmpty() }?.let { payload.put("format", it) }
    val outcome = translate { invoker.invoke("tts.speak", payload) }
    return ResponseEntity
      .ok()
      .contentType(audioType(outcome.contentType))
      .body(outcome.bytes)
  }

  @PostMapping("notify.send", produces = [MediaType.APPLICATION_JSON_VALUE])
  @Operation(summary = "Send a notification through the default notify.send plugin")
  fun notify(
    @RequestBody body: NotifySendDto,
  ): NotifyResultDto {
    val title = body.title.trim()
    val content = body.body.trim()
    if (title.isEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "通知标题不能为空")
    if (title.length > MAX_NOTIFY_TITLE) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "通知标题不得超过 $MAX_NOTIFY_TITLE 字")
    if (content.isEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "通知内容不能为空")
    if (content.length > MAX_NOTIFY_BODY) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "通知内容不得超过 $MAX_NOTIFY_BODY 字")
    val level = body.level?.trim()?.lowercase()?.takeIf { it in NOTIFY_LEVELS } ?: "info"
    val payload = mapper.createObjectNode().put("title", title).put("body", content).put("level", level)
    val outcome = translate { invoker.invoke("notify.send", payload) }
    return NotifyResultDto(delivered = true, pluginId = outcome.pluginId, pluginName = outcome.pluginName)
  }

  private fun audioType(raw: String?): MediaType {
    val parsed = raw?.let { runCatching { MediaType.parseMediaType(it) }.getOrNull() } ?: return MediaType.APPLICATION_OCTET_STREAM
    return if (parsed.type == "audio") parsed else MediaType.APPLICATION_OCTET_STREAM
  }

  private fun <T> translate(block: () -> T): T =
    try {
      block()
    } catch (ex: PluginNotFoundException) {
      throw ResponseStatusException(HttpStatus.NOT_FOUND, ex.message)
    } catch (ex: PluginRequestException) {
      throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.message)
    }

  private companion object {
    private const val MAX_TTS_CHARS = 5000
    private const val MIN_RATE = 0.5
    private const val MAX_RATE = 2.0
    private const val MAX_NOTIFY_TITLE = 200
    private const val MAX_NOTIFY_BODY = 2000
    private val NOTIFY_LEVELS = setOf("info", "warning", "error")
  }
}

data class TtsSpeakDto(
  val text: String = "",
  val voice: String? = null,
  val rate: Double? = null,
  val format: String? = null,
)

data class NotifySendDto(
  val title: String = "",
  val body: String = "",
  val level: String? = null,
)

data class NotifyResultDto(
  val delivered: Boolean,
  val pluginId: String,
  val pluginName: String,
)
