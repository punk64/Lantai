package org.lantai.infrastructure.runtime.install

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger {}

@Component
class RuntimeInstallRunner {
  private val executor = Executors.newFixedThreadPool(2) { runnable ->
    Thread(runnable, "lantai-runtime-install").apply { isDaemon = true }
  }

  private val running = ConcurrentHashMap<String, AtomicBoolean>()

  fun submit(
    kind: String,
    action: () -> RuntimeProgress,
  ): Boolean {
    val flag = running.computeIfAbsent(kind) { AtomicBoolean(false) }
    if (!flag.compareAndSet(false, true)) return false
    executor.submit {
      try {
        action()
      } catch (e: Exception) {
        logger.warn(e) { "Runtime install task for $kind failed" }
      } finally {
        flag.set(false)
      }
    }
    return true
  }

  fun isRunning(kind: String): Boolean = running[kind]?.get() == true

  @PreDestroy
  fun shutdown() {
    executor.shutdownNow()
  }
}
