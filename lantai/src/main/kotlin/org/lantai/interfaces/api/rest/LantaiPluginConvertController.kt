package org.lantai.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.lantai.domain.model.PluginInstance
import org.lantai.domain.persistence.BookRepository
import org.lantai.domain.persistence.PluginStore
import org.lantai.infrastructure.plugin.PluginHttpClient
import org.lantai.infrastructure.plugin.PluginInstanceService
import org.lantai.infrastructure.plugin.readCapabilityEndpoint
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Files
import kotlin.io.path.name

@RestController
@RequestMapping("api/lantai/v1/capabilities", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Lantai Plugin Capabilities")
class LantaiPluginConvertController(
  private val books: BookRepository,
  private val store: PluginStore,
  private val instances: PluginInstanceService,
  private val httpClient: PluginHttpClient,
  private val mapper: com.fasterxml.jackson.databind.ObjectMapper,
) {
  @PostMapping("convert.provide", produces = [MediaType.APPLICATION_OCTET_STREAM_VALUE])
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Convert a book through the plugin that provides convert.provide")
  fun convert(
    @RequestBody body: ConvertRequest,
  ): ResponseEntity<ByteArray> {
    val bookId = body.bookId.trim()
    if (bookId.isEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "bookId 不能为空")
    val target = body.target.trim().lowercase()
    if (!TARGET_PATTERN.matches(target)) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "target 不合法")
    val book = books.findByIdOrNull(bookId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "书籍不存在")
    val source = book.path
    val size = runCatching { Files.size(source) }.getOrElse { throw ResponseStatusException(HttpStatus.NOT_FOUND, "源文件不可读") }
    if (size > MAX_SOURCE_BYTES) throw ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "源文件超过 ${MAX_SOURCE_BYTES / 1024 / 1024} MB 上限")
    val bytes = runCatching { Files.readAllBytes(source) }.getOrElse { throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "源文件读取失败") }
    var failure = "没有插件被授权提供 convert.provide"
    for (plugin in store.findProviders(CAPABILITY)) {
      val ready = runCatching { instances.prepare(plugin.pluginId) }.getOrNull() ?: continue
      val endpoint = ready.endpoint?.takeIf { it.isNotBlank() } ?: continue
      val call =
        runCatching {
          httpClient.invokePostRaw(
            endpoint = endpoint,
            path = "${pathOf(ready)}?bookId=$bookId&target=$target",
            hostKey = ready.hostKey,
            body = bytes,
            contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE,
            timeoutSeconds = TIMEOUT_SECONDS,
            maxResponseBytes = MAX_RESULT_BYTES,
          )
        }.getOrNull()
      if (call?.ok == true) {
        val result = call.body ?: continue
        if (result.isEmpty()) continue
        return ResponseEntity
          .ok()
          .headers {
            it.contentDisposition =
              ContentDisposition
                .attachment()
                .filename("${source.name.substringBeforeLast('.')}.$target")
                .build()
          }.contentType(MediaType.APPLICATION_OCTET_STREAM)
          .contentLength(result.size.toLong())
          .body(result)
      }
      failure = "${ready.name}：${call?.message ?: "转换失败"}"
    }
    throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, failure)
  }

  private fun pathOf(instance: PluginInstance): String {
    val declared =
      runCatching { mapper.readTree(instance.manifest).path("capabilities") }
        .getOrNull()
        ?.let { readCapabilityEndpoint(it, CAPABILITY) }
    return declared ?: "/v1/capabilities/$CAPABILITY"
  }

  private companion object {
    private const val CAPABILITY = "convert.provide"
    private const val TIMEOUT_SECONDS = 120
    private const val MAX_SOURCE_BYTES = 64L * 1024 * 1024
    private const val MAX_RESULT_BYTES = 64 * 1024 * 1024
    private val TARGET_PATTERN = Regex("^[a-z0-9]{1,10}$")
  }
}

data class ConvertRequest(
  val bookId: String = "",
  val target: String = "",
)
