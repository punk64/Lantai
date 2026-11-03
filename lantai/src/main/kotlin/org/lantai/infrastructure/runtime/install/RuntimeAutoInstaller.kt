package org.lantai.infrastructure.runtime.install

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PreDestroy
import org.lantai.infrastructure.calibre.CalibreFeature
import org.lantai.infrastructure.runtime.RuntimeProperties
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}

enum class AutoInstallOutcome {
  SUCCESS,
  SKIPPED,
  FAILED,
  UNKNOWN_KIND,
}

data class AutoInstallReport(
  val kind: String,
  val outcome: AutoInstallOutcome,
  val message: String? = null,
)

data class AutoInstallSnapshot(
  val running: Boolean,
  val startedAt: Instant?,
  val finishedAt: Instant?,
  val reports: List<AutoInstallReport>,
)

@Component
class RuntimeAutoInstaller(
  private val properties: RuntimeProperties,
  private val registry: RuntimeInstallRegistry,
) {
  private val executor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "lantai-runtime-installer").apply { isDaemon = true }
  }

  private val busy = AtomicBoolean(false)
  private val startedAt = AtomicReference<Instant?>(null)
  private val finishedAt = AtomicReference<Instant?>(null)
  private val reports = ConcurrentHashMap<String, AutoInstallReport>()

  @EventListener(ApplicationReadyEvent::class)
  fun onReady() {
    val kinds = properties.autoInstallKinds()
    if (kinds.isEmpty()) return
    logger.info { "Scheduled runtime auto install: ${kinds.joinToString(", ")}" }
    executor.submit { run(kinds) }
  }

  fun trigger(kinds: List<String>): AutoInstallSnapshot {
    val normalized = kinds.map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct()
    executor.submit { run(normalized) }
    return snapshot()
  }

  fun snapshot(): AutoInstallSnapshot =
    AutoInstallSnapshot(
      running = busy.get(),
      startedAt = startedAt.get(),
      finishedAt = finishedAt.get(),
      reports = reports.values.sortedBy { it.kind },
    )

  private fun run(kinds: List<String>) {
    if (!busy.compareAndSet(false, true)) {
      logger.info { "Runtime auto install is already running, request ignored" }
      return
    }
    val begin = Instant.now()
    startedAt.set(begin)
    finishedAt.set(null)
    reports.clear()
    try {
      for (kind in kinds) {
        reports[kind] = process(kind)
      }
    } finally {
      busy.set(false)
      finishedAt.set(Instant.now())
      val elapsed = java.time.Duration.between(begin, Instant.now()).toSeconds()
      val summary = reports.values.joinToString(", ") { "${it.kind}=${it.outcome}" }
      logger.info { "Runtime auto install finished in ${elapsed}s: $summary" }
      if (properties.failFast && reports.values.any { it.outcome == AutoInstallOutcome.FAILED }) {
        logger.error { "Runtime auto install failed and LANTAI_RUNTIME_FAIL_FAST is enabled" }
      }
    }
  }

  private fun process(kind: String): AutoInstallReport {
    if (CalibreFeature.blocked(kind)) {
      logger.info { "$kind install is disabled, skipping download" }
      return AutoInstallReport(kind, AutoInstallOutcome.SKIPPED, CalibreFeature.MESSAGE)
    }
    val installer = registry.get(kind)
    if (installer == null) {
      return AutoInstallReport(kind, AutoInstallOutcome.UNKNOWN_KIND, "Unknown runtime $kind")
    }
    val detection = runCatching { installer.detect() }
    val installed = detection.getOrNull()?.installed == true
    if (installed) {
      logger.info { "$kind is already installed, skipping download" }
      enableIfRequested(kind)
      return AutoInstallReport(kind, AutoInstallOutcome.SKIPPED, "already installed")
    }
    logger.info { "$kind is missing, starting install" }
    return try {
      val progress = installer.pull()
      enableIfRequested(kind)
      AutoInstallReport(kind, AutoInstallOutcome.SUCCESS, progress.message ?: "installed")
    } catch (e: Exception) {
      logger.warn(e) { "$kind auto install failed" }
      AutoInstallReport(kind, AutoInstallOutcome.FAILED, e.message)
    }
  }

  private fun enableIfRequested(kind: String) {
    if (!properties.autoEnable) return
    val toggle = registry.toggle(kind) ?: return
    val result = runCatching { toggle.setEnabled(true) }.getOrNull()
    if (result?.message != null) logger.warn { "$kind enable warning: ${result.message}" }
  }

  @PreDestroy
  fun shutdown() {
    executor.shutdownNow()
  }
}
