package org.lantai.infrastructure.plugin

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.lantai.domain.model.PluginInstance
import org.lantai.domain.persistence.PluginStore
import org.springframework.stereotype.Component
import java.time.LocalDateTime

@Component
class PluginCapabilityInvoker(
  private val store: PluginStore,
  private val instances: PluginInstanceService,
  private val httpClient: PluginHttpClient,
  private val callLog: PluginCallLog,
  private val mapper: ObjectMapper,
) {
  fun providers(): List<CapabilityProvider> =
    SPECS.map { spec ->
      val candidates = store.findProviders(spec.capability)
      val preferred = candidates.firstOrNull()
      CapabilityProvider(
        capability = spec.capability,
        pluginId = preferred?.pluginId,
        pluginName = preferred?.name,
        available = preferred?.isUsable() == true,
        candidates = candidates.size,
      )
    }

  fun invoke(
    capability: String,
    payload: ObjectNode,
  ): CapabilityOutcome {
    val spec = SPECS.firstOrNull { it.capability == capability } ?: throw PluginRequestException("不支持的能力：$capability")
    val candidates = store.findProviders(capability)
    if (candidates.isEmpty()) throw PluginRequestException("没有插件被授权提供 $capability")
    var failure: String? = null
    for (candidate in candidates) {
      val startedDate = LocalDateTime.now()
      val started = System.nanoTime()
      val result = runCatching { attempt(candidate, capability, payload, spec) }
      val durationMs = (System.nanoTime() - started) / 1_000_000
      val outcome = result.getOrNull()
      if (outcome != null) {
        callLog.record(candidate.pluginId, capability, startedDate, durationMs, true, null)
        return outcome
      }
      val message = result.exceptionOrNull()?.message ?: "调用失败"
      callLog.record(candidate.pluginId, capability, startedDate, durationMs, false, message)
      failure = if (message.startsWith(candidate.name)) message else "${candidate.name}：$message"
    }
    throw PluginRequestException(failure ?: "能力调用失败")
  }

  private fun attempt(
    candidate: PluginInstance,
    capability: String,
    payload: ObjectNode,
    spec: Spec,
  ): CapabilityOutcome {
    val ready = instances.prepare(candidate.pluginId)
    val endpoint = ready.endpoint?.takeIf { it.isNotBlank() } ?: throw PluginRequestException("插件地址为空")
    val call =
      httpClient.invokeCapability(
        endpoint = endpoint,
        path = endpointPath(ready, capability),
        hostKey = ready.hostKey,
        payload = mapper.writeValueAsString(payload),
        timeoutSeconds = spec.timeoutSeconds,
        maxResponseBytes = spec.maxResponseBytes,
      )
    if (call.ok && call.status != HTTP_ACCEPTED) {
      return CapabilityOutcome(ready.pluginId, ready.name, call.contentType, call.body ?: ByteArray(0))
    }
    if (!call.ok) throw PluginRequestException(call.message ?: "调用失败")
    val jobId = jobIdOf(call) ?: throw PluginRequestException("插件返回 202 但未给出 jobId")
    val done = awaitJob(endpoint, jobId, ready.hostKey, spec.maxResponseBytes)
    if (!done.ok) throw PluginRequestException(done.message ?: "作业未完成")
    return CapabilityOutcome(ready.pluginId, ready.name, done.contentType, done.body ?: ByteArray(0))
  }

  private fun awaitJob(
    endpoint: String,
    jobId: String,
    hostKey: String?,
    maxResponseBytes: Int,
  ): CapabilityCall {
    val deadline = System.currentTimeMillis() + JOB_MAX_WAIT_MS
    while (System.currentTimeMillis() < deadline) {
      val paused = runCatching { Thread.sleep(JOB_POLL_INTERVAL_MS) }
      if (paused.isFailure) return CapabilityCall.fail("等待作业 $jobId 时被中断")
      val call = httpClient.pollJob(endpoint, jobId, hostKey, maxResponseBytes)
      if (!call.ok) {
        return if (call.status == 404) CapabilityCall.fail("作业 $jobId 已过期或不存在", 404) else call
      }
      if (call.status == HTTP_ACCEPTED) continue
      when (stateOf(call)) {
        "running", "pending", "queued" -> continue
        "failed", "error" -> return CapabilityCall.fail("作业 $jobId 失败：${messageOf(call)}", call.status)
        else -> return call
      }
    }
    return CapabilityCall.fail("作业 $jobId 超过 ${JOB_MAX_WAIT_MS / 60_000} 分钟仍未完成", 504)
  }

  private fun jobIdOf(call: CapabilityCall): String? {
    val raw = call.body ?: return null
    val jobId = runCatching { mapper.readTree(raw).path("jobId").asText("").trim() }.getOrDefault("")
    return jobId.takeIf { it.isNotEmpty() }
  }

  private fun stateOf(call: CapabilityCall): String {
    if (call.contentType?.contains("json", ignoreCase = true) != true) return ""
    val raw = call.body ?: return ""
    return runCatching { mapper.readTree(raw).path("state").asText("").trim().lowercase() }.getOrDefault("")
  }

  private fun messageOf(call: CapabilityCall): String {
    val raw = call.body ?: return "无详情"
    return runCatching { mapper.readTree(raw).path("message").asText("").trim() }.getOrDefault("").ifEmpty { "无详情" }
  }

  private fun endpointPath(
    instance: PluginInstance,
    capability: String,
  ): String {
    val capabilities = runCatching { mapper.readTree(instance.manifest).path("capabilities") }.getOrNull()
    val declared = capabilities?.let { readCapabilityEndpoint(it, capability) }
    return declared ?: "/v1/capabilities/$capability"
  }

  private data class Spec(
    val capability: String,
    val timeoutSeconds: Int,
    val maxResponseBytes: Int,
  )

  private companion object {
    private const val HTTP_ACCEPTED = 202
    private const val JOB_POLL_INTERVAL_MS = 2_000L
    private const val JOB_MAX_WAIT_MS = 30L * 60L * 1_000L
    private val SPECS =
      listOf(
        Spec("tts.speak", 30, 8 * 1024 * 1024),
        Spec("notify.send", 10, 64 * 1024),
        Spec("source.provide", 30, 4 * 1024 * 1024),
      )
  }
}

data class CapabilityProvider(
  val capability: String,
  val pluginId: String?,
  val pluginName: String?,
  val available: Boolean,
  val candidates: Int,
)

data class CapabilityOutcome(
  val pluginId: String,
  val pluginName: String,
  val contentType: String?,
  val bytes: ByteArray,
)
