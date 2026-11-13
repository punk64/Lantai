package org.lantai.infrastructure.plugin

import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

@Component
class PluginCallLog {
  private val records = ConcurrentHashMap<String, ConcurrentLinkedDeque<PluginCallRecord>>()

  fun record(
    pluginId: String,
    capability: String,
    startedDate: LocalDateTime,
    durationMs: Long,
    success: Boolean,
    message: String?,
  ) {
    val deque = records.computeIfAbsent(pluginId) { ConcurrentLinkedDeque() }
    deque.addFirst(
      PluginCallRecord(
        capability = capability,
        startedDate = startedDate,
        durationMs = durationMs,
        success = success,
        message = message?.trim()?.take(MAX_MESSAGE_LENGTH)?.takeIf { it.isNotEmpty() },
      ),
    )
    while (deque.size > MAX_PER_PLUGIN) deque.pollLast()
  }

  fun recent(
    pluginId: String,
    limit: Int,
  ): List<PluginCallRecord> {
    val deque = records[pluginId] ?: return emptyList()
    return deque.take(limit.coerceIn(1, MAX_PER_PLUGIN))
  }

  fun recentAll(limit: Int): Map<String, List<PluginCallRecord>> =
    records.mapValues { (_, deque) -> deque.take(limit.coerceIn(1, MAX_PER_PLUGIN)) }

  private companion object {
    private const val MAX_PER_PLUGIN = 50
    private const val MAX_MESSAGE_LENGTH = 200
  }
}

data class PluginCallRecord(
  val capability: String,
  val startedDate: LocalDateTime,
  val durationMs: Long,
  val success: Boolean,
  val message: String?,
)
