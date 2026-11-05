package org.lantai.infrastructure.runtime

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jooq.DSLContext
import org.lantai.infrastructure.cache.TransientBookCache
import org.lantai.infrastructure.configuration.LantaiSettingsProvider
import org.lantai.infrastructure.jooq.tasks.LantaiTaskQueryDao
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.lang.management.ManagementFactory
import java.time.Instant
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong

private val logger = KotlinLogging.logger {}

private const val COOLDOWN_MS = 30 * 60 * 1000L
private const val TICK_MS = 60 * 1000L
private const val GC_SETTLE_MS = 200L

@Component
class LantaiMemoryReclaimer(
  private val lantaiSettingsProvider: LantaiSettingsProvider,
  private val idleTracker: LantaiIdleTracker,
  private val transientBookCache: TransientBookCache,
  private val lantaiTaskQueryDao: LantaiTaskQueryDao,
  private val dslRW: DSLContext,
  @Qualifier("tasksDslContextRW") private val tasksDslRW: DSLContext,
) {
  private val gate = Semaphore(1)
  private val lastReclaim = AtomicLong(0)
  private val counter = AtomicLong(0)

  @Volatile
  private var lastTrigger: String? = null

  @Volatile
  private var lastOutcome: ReclaimOutcome? = null

  private val concurrentGcSupported: Boolean =
    ManagementFactory
      .getRuntimeMXBean()
      .inputArguments
      .any { it.contains("ExplicitGCInvokesConcurrent") }

  @Scheduled(fixedDelay = TICK_MS)
  fun tick() {
    if (!lantaiSettingsProvider.memoryReclaimEnabled) return
    if (lantaiSettingsProvider.memoryIdleMinutes <= 0) return
    if (!isIdle()) return
    if (lantaiTaskQueryDao.countTasks() > 0) return
    if (System.currentTimeMillis() - lastReclaim.get() < COOLDOWN_MS) return
    reclaim("idle", force = false)
  }

  fun isIdle(): Boolean {
    val minutes = lantaiSettingsProvider.memoryIdleMinutes
    if (minutes <= 0) return false
    return idleTracker.idleMs() >= minutes * 60_000L
  }

  fun status(): MemoryStatus {
    val runtime = Runtime.getRuntime()
    val heapUsed = runtime.totalMemory() - runtime.freeMemory()
    return MemoryStatus(
      enabled = lantaiSettingsProvider.memoryReclaimEnabled,
      idleMinutes = lantaiSettingsProvider.memoryIdleMinutes,
      gcEnabled = lantaiSettingsProvider.memoryGcEnabled,
      concurrentGcSupported = concurrentGcSupported,
      heapUsed = heapUsed,
      heapMax = runtime.maxMemory(),
      heapCommitted = runtime.totalMemory(),
      cacheEntries = transientBookCache.estimatedSize(),
      idleMs = idleTracker.idleMs(),
      lastRequestAt = Instant.ofEpochMilli(idleTracker.lastRequestAt()),
      lastReclaimAt = lastReclaim.get().takeIf { it > 0 }?.let { Instant.ofEpochMilli(it) },
      lastTrigger = lastTrigger,
      reclaimCount = counter.get(),
      lastOutcome = lastOutcome,
    )
  }

  fun reclaim(
    trigger: String,
    force: Boolean,
  ): ReclaimOutcome? {
    if (!force) {
      if (!lantaiSettingsProvider.memoryReclaimEnabled) return null
      if (!isIdle()) return null
    }
    if (!gate.tryAcquire()) return null
    try {
      if (!force && !isIdle()) return null
      return doReclaim(trigger)
    } finally {
      gate.release()
    }
  }

  private fun doReclaim(trigger: String): ReclaimOutcome {
    val runtime = Runtime.getRuntime()
    val before = runtime.totalMemory() - runtime.freeMemory()
    val sqliteShrunk =
      runCatching {
        dslRW.execute("PRAGMA shrink_memory")
        tasksDslRW.execute("PRAGMA shrink_memory")
      }.onFailure { logger.warn(it) { "PRAGMA shrink_memory failed" } }.isSuccess
    val cacheCleared =
      runCatching { transientBookCache.invalidateAll() }
        .onFailure { logger.warn(it) { "Failed to clear transient book cache" } }
        .isSuccess
    var gcRan = false
    var gcSkipped: String? = null
    if (lantaiSettingsProvider.memoryGcEnabled) {
      if (concurrentGcSupported) {
        runCatching { System.gc() }
          .onSuccess { gcRan = true }
          .onFailure { gcSkipped = it.message }
        if (gcRan) {
          runCatching { Thread.sleep(GC_SETTLE_MS) }
        }
      } else {
        gcSkipped = "missing-jvm-flag"
      }
    } else {
      gcSkipped = "disabled"
    }
    val after = runtime.totalMemory() - runtime.freeMemory()
    val outcome =
      ReclaimOutcome(
        at = Instant.now(),
        trigger = trigger,
        heapBefore = before,
        heapAfter = after,
        reclaimedBytes = (before - after).coerceAtLeast(0),
        sqliteShrunk = sqliteShrunk,
        cacheCleared = cacheCleared,
        gcRan = gcRan,
        gcSkipped = gcSkipped,
      )
    lastReclaim.set(System.currentTimeMillis())
    lastTrigger = trigger
    lastOutcome = outcome
    counter.incrementAndGet()
    logger.info {
      "Memory reclaim ($trigger): heap ${before / 1048576}MB -> ${after / 1048576}MB, sqlite=$sqliteShrunk, cache=$cacheCleared, gc=$gcRan"
    }
    return outcome
  }
}

data class MemoryStatus(
  val enabled: Boolean,
  val idleMinutes: Int,
  val gcEnabled: Boolean,
  val concurrentGcSupported: Boolean,
  val heapUsed: Long,
  val heapMax: Long,
  val heapCommitted: Long,
  val cacheEntries: Long,
  val idleMs: Long,
  val lastRequestAt: Instant,
  val lastReclaimAt: Instant?,
  val lastTrigger: String?,
  val reclaimCount: Long,
  val lastOutcome: ReclaimOutcome?,
)

data class ReclaimOutcome(
  val at: Instant,
  val trigger: String,
  val heapBefore: Long,
  val heapAfter: Long,
  val reclaimedBytes: Long,
  val sqliteShrunk: Boolean,
  val cacheCleared: Boolean,
  val gcRan: Boolean,
  val gcSkipped: String?,
)
