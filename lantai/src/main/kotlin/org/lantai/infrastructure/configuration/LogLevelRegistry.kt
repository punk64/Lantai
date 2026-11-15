package org.lantai.infrastructure.configuration

import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.logging.LogLevel
import org.springframework.boot.logging.LoggingSystem
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service

data class LoggerLevel(
  val name: String,
  val configuredLevel: String?,
  val effectiveLevel: String?,
)

@Service
class LogLevelRegistry(
  private val lantaiSettingsProvider: LantaiSettingsProvider,
) {
  private val loggingSystem = LoggingSystem.get(javaClass.classLoader)

  @EventListener(ApplicationReadyEvent::class)
  fun applyPersisted() {
    lantaiSettingsProvider.logLevels.forEach { (name, level) ->
      runCatching { loggingSystem.setLogLevel(name, parseLevel(level)) }
    }
  }

  fun list(): List<LoggerLevel> =
    loggingSystem
      .loggerConfigurations
      .map {
        LoggerLevel(
          name = it.name,
          configuredLevel = it.configuredLevel?.name,
          effectiveLevel = it.effectiveLevel?.name,
        )
      }.sortedBy { it.name }

  fun set(levels: Map<String, String>): List<LoggerLevel> {
    val resolved =
      levels
        .filterKeys { it.isNotBlank() }
        .mapValues { (_, level) -> parseLevel(level) }

    resolved.forEach { (name, level) -> loggingSystem.setLogLevel(name, level) }

    val persisted =
      lantaiSettingsProvider.logLevels.toMutableMap()
    resolved.forEach { (name, level) ->
      if (level == null) persisted.remove(name) else persisted[name] = level.name
    }
    lantaiSettingsProvider.logLevels = persisted

    return list()
  }

  fun reset(names: Collection<String>): List<LoggerLevel> {
    val targets = names.filter { it.isNotBlank() }
    targets.forEach { loggingSystem.setLogLevel(it, null) }
    val persisted = lantaiSettingsProvider.logLevels.toMutableMap()
    targets.forEach { persisted.remove(it) }
    lantaiSettingsProvider.logLevels = persisted
    return list()
  }

  private fun parseLevel(level: String?): LogLevel? {
    val normalized = level?.trim()?.uppercase()
    if (normalized.isNullOrEmpty() || normalized == "INHERIT") return null
    return LogLevel.valueOf(normalized)
  }
}
