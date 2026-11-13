package org.lantai.infrastructure.plugin

import io.github.oshai.kotlinlogging.KotlinLogging
import org.lantai.application.tasks.Task
import org.lantai.application.tasks.TasksRepository
import org.lantai.domain.model.PluginInstance
import org.lantai.domain.model.PluginState
import org.lantai.domain.model.PluginTaskSpec
import org.lantai.domain.persistence.PluginStore
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

@Service
class PluginTaskScheduler(
  private val store: PluginStore,
  private val tasksRepository: TasksRepository,
) {
  private val cursors = ConcurrentHashMap<String, Instant>()
  private val zone = ZoneId.systemDefault()

  @Scheduled(fixedDelay = SCAN_INTERVAL_MS, initialDelay = INITIAL_DELAY_MS)
  fun scan() {
    runCatching { scanDue(Instant.now()) }.onFailure { logger.warn(it) { "Plugin task scan failed" } }
  }

  fun scanDue(now: Instant) {
    val live = mutableSetOf<String>()
    for (instance in store.findAllInstances()) {
      if (!instance.enabled || instance.state !in RUNNABLE_STATES) continue
      val specs = PluginTaskSpecs.parse(instance.manifest)
      if (specs.isEmpty()) continue
      val scheduled = store.findGrantsByPluginId(instance.pluginId).any { it.capability == CAPABILITY }
      for (spec in specs) {
        val key = keyOf(instance, spec)
        live.add(key)
        val since = cursors.put(key, now) ?: now
        if (!scheduled) continue
        val expression = PluginTaskSpecs.cronOf(spec) ?: continue
        if (!PluginTaskSpecs.dueBetween(expression, since, now, zone)) continue
        enqueue(instance, spec)
      }
    }
    cursors.keys.retainAll(live)
  }

  private fun enqueue(
    instance: PluginInstance,
    spec: PluginTaskSpec,
  ) {
    runCatching {
      tasksRepository.save(
        Task.PluginJob(
          pluginId = instance.pluginId,
          taskName = spec.name,
          endpoint = spec.endpoint,
          timeoutSeconds = spec.timeoutSeconds,
        ),
      )
    }.onFailure { logger.warn(it) { "Could not enqueue plugin task ${instance.pluginId}/${spec.name}" } }
  }

  private fun keyOf(
    instance: PluginInstance,
    spec: PluginTaskSpec,
  ): String = "${instance.id}:${spec.name}"

  companion object {
    const val CAPABILITY = "task.schedule"
    private const val SCAN_INTERVAL_MS = 30_000L
    private const val INITIAL_DELAY_MS = 45_000L
    private val RUNNABLE_STATES = setOf(PluginState.READY, PluginState.IDLE)
  }
}
