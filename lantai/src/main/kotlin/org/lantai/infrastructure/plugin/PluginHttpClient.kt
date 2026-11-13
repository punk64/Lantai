package org.lantai.infrastructure.plugin

import com.fasterxml.jackson.databind.ObjectMapper
import org.lantai.domain.model.PluginState
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Component
class PluginHttpClient(
  @param:Value($$"${application.version:0.0.0}") private val hostVersion: String,
) {
  private val client =
    HttpClient
      .newBuilder()
      .connectTimeout(Duration.ofSeconds(3))
      .followRedirects(HttpClient.Redirect.NEVER)
      .build()

  private val mapper = ObjectMapper()

  fun handshake(
    endpoint: String,
    hostKey: String?,
  ): PluginHandshake {
    val base = runCatching { requireLoopback(endpoint) }.getOrElse { return PluginHandshake(PluginState.INCOMPATIBLE, it.message) }
    val manifestCall = get(base, "/v1/manifest", hostKey, MANIFEST_TIMEOUT)
    when (manifestCall.kind) {
      CallKind.UNREACHABLE -> return PluginHandshake(PluginState.UNAVAILABLE, manifestCall.message)
      CallKind.UNAUTHORIZED -> return PluginHandshake(PluginState.AUTH_FAILED, manifestCall.message)
      CallKind.BAD_STATUS -> return PluginHandshake(PluginState.UNAVAILABLE, manifestCall.message)
      CallKind.OK -> Unit
    }
    val node = runCatching { mapper.readTree(manifestCall.body) }.getOrNull()
      ?: return PluginHandshake(PluginState.INCOMPATIBLE, "清单不是合法 JSON")
    val protocol = node.path("protocol").asInt(node.path("apiVersion").asInt(0))
    if (protocol !in SUPPORTED_PROTOCOLS) return PluginHandshake(PluginState.INCOMPATIBLE, "插件协议版本 $protocol 不受支持")
    val minHost = node.path("minHost").asText("").trim()
    if (minHost.isNotEmpty() && isBelow(hostVersion, minHost)) {
      return PluginHandshake(PluginState.INCOMPATIBLE, "插件要求宿主版本不低于 $minHost，当前 $hostVersion")
    }
    val pluginId = node.path("id").asText("").trim()
    if (pluginId.isEmpty()) return PluginHandshake(PluginState.INCOMPATIBLE, "清单缺少 id")
    val name = node.path("name").asText("").trim().ifEmpty { pluginId }
    val version = node.path("version").asText("").trim()
    if (version.isEmpty()) return PluginHandshake(PluginState.INCOMPATIBLE, "清单缺少 version")
    val health = get(base, "/v1/health", hostKey, HEALTH_TIMEOUT)
    when (health.kind) {
      CallKind.UNREACHABLE -> return PluginHandshake(PluginState.UNAVAILABLE, health.message)
      CallKind.UNAUTHORIZED -> return PluginHandshake(PluginState.AUTH_FAILED, health.message)
      CallKind.BAD_STATUS -> return PluginHandshake(PluginState.UNAVAILABLE, health.message)
      CallKind.OK -> Unit
    }
    return PluginHandshake(
      state = PluginState.READY,
      message = null,
      manifest =
        PluginManifest(
          pluginId = pluginId,
          name = name,
          version = version,
          apiVersion = protocol,
          runtimeKind = node.path("runtime").asText("").trim().ifEmpty { "binary" },
          entry = node.path("entry").asText("").trim().ifEmpty { null },
          capabilities = readCapabilityNames(node.path("capabilities")),
          raw = node.toString(),
        ),
    )
  }

  fun ping(
    endpoint: String,
    hostKey: String?,
  ): Boolean {
    val base = runCatching { requireLoopback(endpoint) }.getOrElse { return false }
    return get(base, "/v1/health", hostKey, HEALTH_TIMEOUT).kind == CallKind.OK
  }

  fun invokeJob(
    endpoint: String,
    path: String,
    hostKey: String?,
    timeoutSeconds: Int,
  ): String? {
    val base = runCatching { requireLoopback(endpoint) }.getOrElse { return it.message ?: "插件地址不合法" }
    val resolved = runCatching { base.resolve(path) }.getOrElse { return "任务地址拼接失败" }
    if (resolved.host != base.host || resolved.port != base.port) return "任务地址不得跨主机"
    if (resolved.scheme != base.scheme) return "任务地址不得改变协议"
    val effectiveTimeout = timeoutSeconds.coerceIn(PluginTaskSpecs.MIN_TIMEOUT_SECONDS, PluginTaskSpecs.MAX_TIMEOUT_SECONDS).toLong()
    val builder =
      HttpRequest
        .newBuilder(resolved)
        .timeout(Duration.ofSeconds(effectiveTimeout))
        .header("Content-Type", "application/json")
        .header("Accept", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString("{}"))
    if (!hostKey.isNullOrBlank()) builder.header(HOST_KEY_HEADER, hostKey)
    val response =
      runCatching { client.send(builder.build(), HttpResponse.BodyHandlers.ofString()) }
        .getOrElse { return "插件无响应：${it.message ?: it::class.simpleName}" }
    return when {
      response.statusCode() == 401 || response.statusCode() == 403 -> "hostKey 校验失败（HTTP ${response.statusCode()}）"
      response.statusCode() !in 200..299 -> "$path 返回 HTTP ${response.statusCode()}"
      else -> null
    }
  }

  fun invokeCapability(
    endpoint: String,
    path: String,
    hostKey: String?,
    payload: String,
    timeoutSeconds: Int,
    maxResponseBytes: Int,
  ): CapabilityCall {
    val base = runCatching { requireLoopback(endpoint) }.getOrElse { return CapabilityCall.fail(it.message ?: "插件地址不合法") }
    val resolved = runCatching { base.resolve(path) }.getOrElse { return CapabilityCall.fail("能力地址拼接失败") }
    if (resolved.host != base.host || resolved.port != base.port) return CapabilityCall.fail("能力地址不得跨主机")
    if (resolved.scheme != base.scheme) return CapabilityCall.fail("能力地址不得改变协议")
    val effectiveTimeout = timeoutSeconds.coerceIn(MIN_CALL_TIMEOUT_SECONDS, MAX_CALL_TIMEOUT_SECONDS).toLong()
    val builder =
      HttpRequest
        .newBuilder(resolved)
        .timeout(Duration.ofSeconds(effectiveTimeout))
        .header("Content-Type", "application/json")
        .header("Accept", "*/*")
        .POST(HttpRequest.BodyPublishers.ofString(payload, Charsets.UTF_8))
    if (!hostKey.isNullOrBlank()) builder.header(HOST_KEY_HEADER, hostKey)
    val response =
      runCatching { client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream()) }
        .getOrElse { return CapabilityCall.fail("插件无响应：${it.message ?: it::class.simpleName}") }
    return consume(response, maxResponseBytes)
  }

  fun invokeGet(
    endpoint: String,
    path: String,
    hostKey: String?,
    timeoutSeconds: Int,
    maxResponseBytes: Int,
  ): CapabilityCall {
    val base = runCatching { requireLoopback(endpoint) }.getOrElse { return CapabilityCall.fail(it.message ?: "插件地址不合法") }
    val resolved = runCatching { base.resolve(path) }.getOrElse { return CapabilityCall.fail("能力地址拼接失败") }
    if (resolved.host != base.host || resolved.port != base.port) return CapabilityCall.fail("能力地址不得跨主机")
    if (resolved.scheme != base.scheme) return CapabilityCall.fail("能力地址不得改变协议")
    val effectiveTimeout = timeoutSeconds.coerceIn(MIN_CALL_TIMEOUT_SECONDS, MAX_CALL_TIMEOUT_SECONDS).toLong()
    val builder =
      HttpRequest
        .newBuilder(resolved)
        .timeout(Duration.ofSeconds(effectiveTimeout))
        .header("Accept", "*/*")
        .GET()
    if (!hostKey.isNullOrBlank()) builder.header(HOST_KEY_HEADER, hostKey)
    val response =
      runCatching { client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream()) }
        .getOrElse { return CapabilityCall.fail("插件无响应：${it.message ?: it::class.simpleName}") }
    return consume(response, maxResponseBytes)
  }

  fun invokePostRaw(
    endpoint: String,
    path: String,
    hostKey: String?,
    body: ByteArray,
    contentType: String,
    timeoutSeconds: Int,
    maxResponseBytes: Int,
  ): CapabilityCall {
    val base = runCatching { requireLoopback(endpoint) }.getOrElse { return CapabilityCall.fail(it.message ?: "插件地址不合法") }
    val resolved = runCatching { base.resolve(path) }.getOrElse { return CapabilityCall.fail("能力地址拼接失败") }
    if (resolved.host != base.host || resolved.port != base.port) return CapabilityCall.fail("能力地址不得跨主机")
    if (resolved.scheme != base.scheme) return CapabilityCall.fail("能力地址不得改变协议")
    val effectiveTimeout = timeoutSeconds.coerceIn(MIN_CALL_TIMEOUT_SECONDS, MAX_CALL_TIMEOUT_SECONDS).toLong()
    val builder =
      HttpRequest
        .newBuilder(resolved)
        .timeout(Duration.ofSeconds(effectiveTimeout))
        .header("Content-Type", contentType)
        .header("Accept", "*/*")
        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
    if (!hostKey.isNullOrBlank()) builder.header(HOST_KEY_HEADER, hostKey)
    val response =
      runCatching { client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream()) }
        .getOrElse { return CapabilityCall.fail("插件无响应：${it.message ?: it::class.simpleName}") }
    return consume(response, maxResponseBytes)
  }

  fun pollJob(
    endpoint: String,
    jobId: String,
    hostKey: String?,
    maxResponseBytes: Int,
  ): CapabilityCall {
    if (!JOB_ID_PATTERN.matches(jobId)) return CapabilityCall.fail("jobId 不合法")
    val base = runCatching { requireLoopback(endpoint) }.getOrElse { return CapabilityCall.fail(it.message ?: "插件地址不合法") }
    val resolved = runCatching { base.resolve("/v1/jobs/$jobId") }.getOrElse { return CapabilityCall.fail("作业地址拼接失败") }
    if (resolved.host != base.host || resolved.port != base.port) return CapabilityCall.fail("作业地址不得跨主机")
    if (resolved.scheme != base.scheme) return CapabilityCall.fail("作业地址不得改变协议")
    val builder =
      HttpRequest
        .newBuilder(resolved)
        .timeout(Duration.ofSeconds(JOB_TIMEOUT_SECONDS))
        .header("Accept", "*/*")
        .GET()
    if (!hostKey.isNullOrBlank()) builder.header(HOST_KEY_HEADER, hostKey)
    val response =
      runCatching { client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream()) }
        .getOrElse { return CapabilityCall.fail("插件无响应：${it.message ?: it::class.simpleName}") }
    return consume(response, maxResponseBytes)
  }

  private fun consume(
    response: HttpResponse<InputStream>,
    maxResponseBytes: Int,
  ): CapabilityCall {
    response.body().use { stream ->
      val status = response.statusCode()
      if (status == 401 || status == 403) return CapabilityCall.fail("hostKey 校验失败（HTTP $status）", status)
      if (status !in 200..299) {
        val detail = runCatching { readLimited(stream, ERROR_SNIPPET_BYTES) }.getOrNull()?.toString(Charsets.UTF_8)?.trim()
        val suffix = if (detail.isNullOrEmpty()) "" else "：${detail.take(200)}"
        return CapabilityCall.fail("插件返回 HTTP $status$suffix", status)
      }
      val declared = response.headers().firstValueAsLong(CONTENT_LENGTH).orElse(-1L)
      if (declared > maxResponseBytes) return CapabilityCall.fail("能力响应超过 $maxResponseBytes 字节上限", status)
      val body =
        runCatching { readLimited(stream, maxResponseBytes) }
          .getOrElse { return CapabilityCall.fail("能力响应超过 $maxResponseBytes 字节上限", status) }
      return CapabilityCall(true, status, response.headers().firstValue("Content-Type").orElse(null), body, null)
    }
  }

  private fun readLimited(
    stream: InputStream,
    limit: Int,
  ): ByteArray {
    val buffer = ByteArrayOutputStream()
    val chunk = ByteArray(8192)
    var total = 0
    while (true) {
      val read = stream.read(chunk)
      if (read < 0) break
      total += read
      if (total > limit) throw IllegalArgumentException("响应超过上限")
      buffer.write(chunk, 0, read)
    }
    return buffer.toByteArray()
  }

  private fun get(
    base: URI,
    path: String,
    hostKey: String?,
    timeout: Duration,
  ): CallResult {
    val target = runCatching { base.resolve(path) }.getOrElse { return CallResult(CallKind.UNREACHABLE, null, "地址拼接失败") }
    val builder = HttpRequest.newBuilder(target).timeout(timeout).GET()
    if (!hostKey.isNullOrBlank()) builder.header(HOST_KEY_HEADER, hostKey)
    val response =
      runCatching { client.send(builder.build(), HttpResponse.BodyHandlers.ofString()) }
        .getOrElse { return CallResult(CallKind.UNREACHABLE, null, "插件无响应：${it.message ?: it::class.simpleName}") }
    val status = response.statusCode()
    return when {
      status == 401 || status == 403 -> CallResult(CallKind.UNAUTHORIZED, null, "hostKey 校验失败（HTTP $status）")
      status !in 200..299 -> CallResult(CallKind.BAD_STATUS, null, "$path 返回 HTTP $status")
      else -> CallResult(CallKind.OK, response.body(), null)
    }
  }

  private fun requireLoopback(raw: String): URI {
    val uri = runCatching { URI.create(raw.trim().trimEnd('/')) }.getOrElse { throw IllegalArgumentException("插件地址不合法") }
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") throw IllegalArgumentException("插件地址必须使用 http(s)")
    val host = uri.host?.lowercase()?.removeSurrounding("[", "]") ?: throw IllegalArgumentException("插件地址缺少主机")
    if (host !in LOOPBACK_HOSTS) throw IllegalArgumentException("插件地址必须是本机回环地址")
    if (uri.port !in 1..65535) throw IllegalArgumentException("插件地址缺少有效端口")
    return URI("$scheme://${if (host.contains(':')) "[$host]" else host}:${uri.port}")
  }

  private fun isBelow(
    current: String,
    required: String,
  ): Boolean {
    val left = current.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    val right = required.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    for (index in 0 until maxOf(left.size, right.size)) {
      val a = left.getOrElse(index) { 0 }
      val b = right.getOrElse(index) { 0 }
      if (a != b) return a < b
    }
    return false
  }

  private companion object {
    private val HEALTH_TIMEOUT: Duration = Duration.ofSeconds(3)
    private val MANIFEST_TIMEOUT: Duration = Duration.ofSeconds(5)
    private val SUPPORTED_PROTOCOLS = setOf(1, 2)
    private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1", "0:0:0:0:0:0:0:1")
    private const val HOST_KEY_HEADER = "X-Lantai-Host-Key"
    private const val MIN_CALL_TIMEOUT_SECONDS = 5
    private const val MAX_CALL_TIMEOUT_SECONDS = 120
    private const val ERROR_SNIPPET_BYTES = 512
    private const val CONTENT_LENGTH = "Content-Length"
    private const val JOB_TIMEOUT_SECONDS = 15L
    private val JOB_ID_PATTERN = Regex("^[A-Za-z0-9._-]{1,128}$")
  }
}

data class CapabilityCall(
  val ok: Boolean,
  val status: Int,
  val contentType: String?,
  val body: ByteArray?,
  val message: String?,
) {
  companion object {
    fun fail(
      message: String,
      status: Int = 0,
    ): CapabilityCall = CapabilityCall(false, status, null, null, message)
  }
}

enum class CallKind {
  OK,
  UNREACHABLE,
  UNAUTHORIZED,
  BAD_STATUS,
}

private data class CallResult(
  val kind: CallKind,
  val body: String?,
  val message: String?,
)

data class PluginHandshake(
  val state: PluginState,
  val message: String?,
  val manifest: PluginManifest? = null,
)

data class PluginManifest(
  val pluginId: String,
  val name: String,
  val version: String,
  val apiVersion: Int,
  val runtimeKind: String,
  val entry: String?,
  val capabilities: List<String>,
  val raw: String,
)
