package org.lantai.infrastructure.plugin

import org.lantai.application.tasks.Task
import org.springframework.stereotype.Service

@Service
class PluginJobRunner(
  private val instances: PluginInstanceService,
  private val httpClient: PluginHttpClient,
) {
  fun run(job: Task.PluginJob) {
    val instance = instances.prepare(job.pluginId)
    val endpoint = instance.endpoint?.takeIf { it.isNotBlank() } ?: throw PluginRequestException("插件 ${job.pluginId} 地址为空")
    val failure = httpClient.invokeJob(endpoint, job.endpoint, instance.hostKey, job.timeoutSeconds)
    if (failure != null) throw PluginJobException(failure)
  }
}

class PluginJobException(message: String) : RuntimeException(message)
