package org.lantai.infrastructure.plugin

import io.github.oshai.kotlinlogging.KotlinLogging
import org.lantai.application.tasks.Task
import org.lantai.application.tasks.TasksRepository
import org.lantai.domain.model.PluginInstance
import org.lantai.domain.model.PluginMode
import org.lantai.domain.model.PluginState
import org.lantai.domain.persistence.PluginStore
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

@Service
class PluginHealthMonitor(
  private val store: PluginStore,
  private val httpClient: PluginHttpClient,
  private val processManager: PluginProcessManager,
  private val tasksRepository: TasksRepository,
  @param:Value($$"${lantai.plugin.monitor.enabled:true}") private val enabled: Boolean,
  @param:Value($$"${lantai.plugin.monitor.idle-minutes:60}") private val idleMinutes: Long,
) {
  private val failures = ConcurrentHashMap<String, Int>()

  @Scheduled(fixedDelay = TICK_MS, initialDelay = TICK_MS)
  fun tick() {
    if (!enabled) return
    runCatching { probeAll() }.onFailure { logger.warn(it) { "Plugin health probe failed" } }
    runCatching { reclaimIdle() }.onFailure { logger.warn(it) { "Plugin idle reclaim failed" } }
  }

  fun probeAll() {
    val targets = store.findAllInstances().filter { it.enabled && it.state == PluginState.READY }
    targets.forEach { instance ->
      if (alive(instance)) {
        failures.remove(instance.id)
      } else {
        val count = failures.merge(instance.id, 1, Int::plus) ?: 1
        if (count >= FAILURE_THRESHOLD) {
          failures.remove(instance.id)
          degrade(instance, count)
        }
      }
    }
    failures.keys.retainAll(targets.map { it.id }.toSet())
  }

  fun reclaimIdle() {
    if (idleMinutes <= 0L) return
    val cutoff = LocalDateTime.now().minusMinutes(idleMinutes)
    store.findAllInstances()
      .filter { it.mode == PluginMode.MANAGED && it.enabled && it.state == PluginState.READY }
      .filter { it.lastUsedDate?.isBefore(cutoff) == true }
      .filter { !hasPendingJob(it.pluginId) }
      .forEach { instance ->
        processManager.stop(instance.id)
        failures.remove(instance.id)
        store.updateInstance(
          instance.copy(
            state = PluginState.IDLE,
            statusMessage = "空闲超过 $idleMinutes 分钟，进程已回收",
            pid = null,
            lastModifiedDate = LocalDateTime.now(),
          ),
        )
        logger.info { "Reclaimed idle plugin ${instance.pluginId}" }
      }
  }

  private fun hasPendingJob(pluginId: String): Boolean {
    val queued = tasksRepository.findAll().any { it is Task.PluginJob && it.pluginId == pluginId }
    if (queued) return true
    return tasksRepository
      .findAllGroupedByOwner()
      .values
      .flatten()
      .any { it is Task.PluginJob && it.pluginId == pluginId }
  }

  private fun alive(instance: PluginInstance): Boolean {
    val endpoint = instance.endpoint?.takeIf { it.isNotBlank() } ?: return false
    return httpClient.ping(endpoint, instance.hostKey)
  }

  private fun degrade(
    instance: PluginInstance,
    failed: Int,
  ) {
    if (processAlive(instance)) {
      val message = "健康探测连续 $failed 次失败，进程仍在运行"
      if (instance.statusMessage != message) {
        store.updateInstance(instance.copy(statusMessage = message, lastModifiedDate = LocalDateTime.now()))
      }
      logger.warn { "Plugin ${instance.pluginId} probe failed $failed times but process is alive" }
      return
    }
    processManager.stop(instance.id)
    store.updateInstance(
      instance.copy(
        state = PluginState.UNAVAILABLE,
        statusMessage = "健康探测连续 $failed 次失败，进程已退出",
        pid = null,
        lastModifiedDate = LocalDateTime.now(),
      ),
    )
    logger.warn { "Plugin ${instance.pluginId} degraded after $failed failed probes" }
  }

  private fun processAlive(instance: PluginInstance): Boolean =
    instance.pid?.let { pid ->
      ProcessHandle.of(pid.toLong()).map { handle -> handle.isAlive }.orElse(false)
    } ?: false

  companion object {
    const val FAILURE_THRESHOLD = 3
    const val IDLE_MINUTES = 60L
    private const val TICK_MS = 60_000L
  }
}
