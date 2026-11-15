package org.lantai.infrastructure.metadata.scrape

import io.github.oshai.kotlinlogging.KotlinLogging
import org.lantai.domain.model.ScrapeCandidate
import org.lantai.domain.persistence.ScrapeQuotaRepository
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

enum class ScrapePurpose {
  SEARCH,
  MATCH,
}

class ScrapeBackoffException(
  sourceId: String,
  val retryAfterSeconds: Int,
) : RuntimeException("Source $sourceId is backing off for ${retryAfterSeconds}s")

class ScrapeCircuitOpenException(
  sourceId: String,
  val retryAfterSeconds: Int,
) : RuntimeException("Circuit for source $sourceId is open for ${retryAfterSeconds}s")

data class ScrapeSourceState(
  val usageSearch: Int,
  val usageMatch: Int,
  val consecutiveFailures: Int,
  val backoffSeconds: Long,
  val circuitSeconds: Long,
)

@Service
class ScrapeRateLimiter(
  private val properties: ScrapeProperties,
  private val quotaRepository: ScrapeQuotaRepository,
) {
  private val states = ConcurrentHashMap<String, SourceState>()
  private val cache = ConcurrentHashMap<String, CachedSearch>()
  private val quotaOverrides = ConcurrentHashMap<String, Int>()
  private val intervalOverrides = ConcurrentHashMap<String, Long>()

  private val backoffSteps = longArrayOf(30, 120, 600)
  private val circuitThreshold = 3
  private val circuitOpenSeconds = 1800L
  private val cacheTtl: Duration = Duration.ofHours(24)

  fun cached(
    sourceId: String,
    query: String,
    limit: Int,
  ): List<ScrapeCandidate>? {
    val key = cacheKey(sourceId, query, limit)
    val entry = cache[key] ?: return null
    if (entry.createdAt.plus(cacheTtl).isBefore(Instant.now())) {
      cache.remove(key)
      return null
    }
    return entry.results
  }

  fun cache(
    sourceId: String,
    query: String,
    limit: Int,
    results: List<ScrapeCandidate>,
  ) {
    cache[cacheKey(sourceId, query, limit)] = CachedSearch(Instant.now(), results)
  }

  fun acquire(
    sourceId: String,
    purpose: ScrapePurpose = ScrapePurpose.SEARCH,
  ) {
    val state = state(sourceId)
    val waitUntil =
      synchronized(state) {
        val now = System.currentTimeMillis()
        if (state.circuitOpenUntil > now) {
          throw ScrapeCircuitOpenException(sourceId, ((state.circuitOpenUntil - now) / 1000).toInt().coerceAtLeast(1))
        }
        if (state.backoffUntil > now) {
          throw ScrapeBackoffException(sourceId, ((state.backoffUntil - now) / 1000).toInt().coerceAtLeast(1))
        }
        val quota = quota(sourceId, purpose)
        if (quota > 0 && quotaRepository.usage(sourceId, LocalDate.now(), purpose.name) >= quota) {
          throw ScrapeQuotaExceededException(sourceId)
        }
        val interval = intervalOverrides[sourceId] ?: properties.source(sourceId).minIntervalMs
        val slot = maxOf(now, state.nextAllowedAt)
        state.nextAllowedAt = slot + interval
        slot
      }
    val wait = waitUntil - System.currentTimeMillis()
    if (wait > 0) Thread.sleep(wait)
  }

  fun record(
    sourceId: String,
    purpose: ScrapePurpose = ScrapePurpose.SEARCH,
  ) {
    quotaRepository.increment(sourceId, LocalDate.now(), purpose.name)
    val state = state(sourceId)
    synchronized(state) {
      state.consecutiveFailures = 0
      state.backoffUntil = 0
    }
  }

  fun checkQuota(
    sourceId: String,
    purpose: ScrapePurpose = ScrapePurpose.SEARCH,
  ) {
    val quota = quota(sourceId, purpose)
    if (quota <= 0) return
    if (quotaRepository.usage(sourceId, LocalDate.now(), purpose.name) >= quota) {
      throw ScrapeQuotaExceededException(sourceId)
    }
  }

  fun noteFailure(sourceId: String) {
    val state = state(sourceId)
    synchronized(state) {
      state.consecutiveFailures++
      val index = (state.consecutiveFailures - 1).coerceIn(0, backoffSteps.size - 1)
      state.backoffUntil = System.currentTimeMillis() + backoffSteps[index] * 1000
      if (state.consecutiveFailures >= circuitThreshold) {
        state.circuitOpenUntil = System.currentTimeMillis() + circuitOpenSeconds * 1000
        logger.warn { "Circuit opened for scrape source $sourceId after ${state.consecutiveFailures} consecutive failures" }
      }
    }
  }

  fun usage(
    sourceId: String,
    purpose: ScrapePurpose = ScrapePurpose.SEARCH,
  ): Int = quotaRepository.usage(sourceId, LocalDate.now(), purpose.name)

  fun quota(
    sourceId: String,
    purpose: ScrapePurpose = ScrapePurpose.SEARCH,
  ): Int =
    when (purpose) {
      ScrapePurpose.SEARCH -> quotaOverrides[sourceId] ?: properties.source(sourceId).dailyQuota
      ScrapePurpose.MATCH -> properties.matchDailyQuota
    }

  fun overrideQuota(
    sourceId: String,
    quota: Int,
  ) {
    if (quota <= 0) quotaOverrides.remove(sourceId) else quotaOverrides[sourceId] = quota
  }

  fun overrideInterval(
    sourceId: String,
    intervalMs: Long,
  ) {
    if (intervalMs <= 0) intervalOverrides.remove(sourceId) else intervalOverrides[sourceId] = intervalMs
  }

  fun summary(sourceId: String): ScrapeSourceState {
    val internal = states.computeIfAbsent(sourceId) { SourceState() }
    val now = System.currentTimeMillis()
    return synchronized(internal) {
      ScrapeSourceState(
        usageSearch = quotaRepository.usage(sourceId, LocalDate.now(), ScrapePurpose.SEARCH.name),
        usageMatch = quotaRepository.usage(sourceId, LocalDate.now(), ScrapePurpose.MATCH.name),
        consecutiveFailures = internal.consecutiveFailures,
        backoffSeconds = ((internal.backoffUntil - now) / 1000).coerceAtLeast(0),
        circuitSeconds = ((internal.circuitOpenUntil - now) / 1000).coerceAtLeast(0),
      )
    }
  }

  fun resetCircuit(sourceId: String) {
    val state = state(sourceId)
    synchronized(state) {
      state.consecutiveFailures = 0
      state.backoffUntil = 0
      state.circuitOpenUntil = 0
    }
  }

  private fun state(sourceId: String): SourceState = states.computeIfAbsent(sourceId) { SourceState() }

  private fun cacheKey(
    sourceId: String,
    query: String,
    limit: Int,
  ): String = "$sourceId|${query.trim().lowercase()}|$limit"

  private class SourceState {
    var nextAllowedAt: Long = 0
    var consecutiveFailures: Int = 0
    var backoffUntil: Long = 0
    var circuitOpenUntil: Long = 0
  }

  private class CachedSearch(
    val createdAt: Instant,
    val results: List<ScrapeCandidate>,
  )
}
