package org.lantai.infrastructure.online

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

private val logger = KotlinLogging.logger {}

data class ExtInstallTask(
  val pkgName: String,
  val phase: String,
  val startedAt: Instant,
)

data class ExtInstallResult(
  val pkgName: String,
  val success: Boolean,
  val message: String?,
  val finishedAt: Instant,
)

data class ExtInstallState(
  val running: List<ExtInstallTask>,
  val results: List<ExtInstallResult>,
)

@Component
class ExtensionInstallRegistry {
  private val running = ConcurrentHashMap<String, ExtInstallTask>()
  private val results = ConcurrentHashMap<String, ExtInstallResult>()
  private val pool =
    Executors.newSingleThreadExecutor { r ->
      Thread(r, "lantai-ext-install").apply { isDaemon = true }
    }

  fun start(pkgName: String, action: () -> SuwayomiActionResult): Boolean {
    if (pkgName.isBlank()) return false
    val task = ExtInstallTask(pkgName, "QUEUED", Instant.now())
    if (running.putIfAbsent(pkgName, task) != null) return false
    pool.execute {
      running[pkgName] = task.copy(phase = "RUNNING")
      val outcome =
        try {
          action()
        } catch (e: Exception) {
          logger.warn(e) { "Extension install failed for $pkgName" }
          SuwayomiActionResult(false, e.message ?: e.javaClass.simpleName)
        }
      running.remove(pkgName)
      results[pkgName] = ExtInstallResult(pkgName, outcome.success, outcome.message, Instant.now())
    }
    return true
  }

  fun state(): ExtInstallState {
    prune()
    return ExtInstallState(
      running = running.values.sortedBy { it.startedAt },
      results = results.values.sortedBy { it.finishedAt },
    )
  }

  fun consume(pkgName: String): Boolean = results.remove(pkgName) != null

  private fun prune() {
    val deadline = Instant.now().minus(Duration.ofMinutes(10))
    results.keys.removeIf { key -> results[key]?.finishedAt?.isBefore(deadline) == true }
  }

  @PreDestroy
  fun shutdown() {
    pool.shutdownNow()
  }
}
