package org.lantai.infrastructure.metadata.scrape

import com.github.f4b6a3.tsid.TsidCreator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.lantai.domain.model.ScrapeCandidate
import org.lantai.domain.model.ScrapeMatch
import org.lantai.domain.model.Series
import org.lantai.domain.persistence.ScrapeMatchRepository
import org.lantai.domain.persistence.SeriesMetadataRepository
import org.lantai.domain.persistence.SeriesRepository
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

private val logger = KotlinLogging.logger {}

data class ScrapeMatchJob(
  val id: String,
  val libraryId: String,
  val total: Int,
  val processed: Int,
  val created: Int,
  val failed: Int,
  val status: String,
  val message: String?,
)

@Service
class ScrapeMatcher(
  private val registry: ScrapeSourceRegistry,
  private val rateLimiter: ScrapeRateLimiter,
  private val properties: ScrapeProperties,
  private val seriesRepository: SeriesRepository,
  private val seriesMetadataRepository: SeriesMetadataRepository,
  private val matchRepository: ScrapeMatchRepository,
) {
  private val jobs = ConcurrentHashMap<String, JobState>()

  private val executor =
    Executors.newSingleThreadExecutor { runnable ->
      Thread(runnable, "lantai-scrape-match").apply { isDaemon = true }
    }

  fun startJob(
    libraryId: String,
    limit: Int,
  ): ScrapeMatchJob {
    val targets = seriesRepository.findAllByLibraryId(libraryId).take(limit.coerceAtLeast(1))
    val state = JobState(LibraryJob(TsidCreator.getTsid256().toString(), libraryId, targets.size))
    jobs[state.job.id] = state
    executor.submit {
      state.status = "RUNNING"
      targets.forEach { series ->
        try {
          if (matchSeries(series) != null) state.created++ else state.skipped++
        } catch (e: Exception) {
          logger.warn(e) { "Failed to match series ${series.id}" }
          state.failed++
        }
        state.processed++
      }
      state.status = "DONE"
    }
    return state.toDto()
  }

  fun job(jobId: String): ScrapeMatchJob? = jobs[jobId]?.toDto()

  fun jobs(): List<ScrapeMatchJob> = jobs.values.map { it.toDto() }

  fun matchSeries(seriesId: String): ScrapeMatch? {
    val series = seriesRepository.findByIdOrNull(seriesId) ?: return null
    return matchSeries(series)
  }

  private fun matchSeries(series: Series): ScrapeMatch? {
    if (matchRepository.findBySeriesId(series.id).any { it.status == ScrapeMatch.Status.PENDING }) return null
    val query = displayTitle(series)
    if (query.isBlank()) return null
    val best = bestCandidate(query) ?: return null
    if (best.second < MIN_THRESHOLD) return null
    val match =
      ScrapeMatch(
        seriesId = series.id,
        seriesTitle = query,
        source = best.first.source,
        sourceId = best.first.sourceId,
        score = best.second,
        candidate = best.first,
      )
    matchRepository.insert(match)
    return match
  }

  private fun bestCandidate(query: String): Pair<ScrapeCandidate, Double>? {
    var best: Pair<ScrapeCandidate, Double>? = null
    registry.enabled().forEach { source ->
      val candidates = searchWithGuard(source, query) ?: return@forEach
      candidates.forEach { candidate ->
        val score = maxOf(similarity(query, candidate.title), similarity(query, candidate.subtitle))
        if (best == null || score > best!!.second) best = candidate to score
      }
    }
    return best
  }

  private fun searchWithGuard(
    source: ScrapeSource,
    query: String,
  ): List<ScrapeCandidate>? {
    rateLimiter.cached(source.id, query, SEARCH_LIMIT)?.let { return it }
    return try {
      rateLimiter.checkQuota(source.id, ScrapePurpose.MATCH)
      rateLimiter.acquire(source.id, ScrapePurpose.MATCH)
      val results = source.search(query, SEARCH_LIMIT)
      rateLimiter.record(source.id, ScrapePurpose.MATCH)
      rateLimiter.cache(source.id, query, SEARCH_LIMIT, results)
      results
    } catch (e: ScrapeQuotaExceededException) {
      logger.warn { "Match quota exhausted for ${source.id}" }
      null
    } catch (e: ScrapeBackoffException) {
      logger.warn { "Source ${source.id} backing off ${e.retryAfterSeconds}s" }
      null
    } catch (e: ScrapeCircuitOpenException) {
      logger.warn { "Source ${source.id} circuit open ${e.retryAfterSeconds}s" }
      null
    } catch (e: ScrapeException) {
      rateLimiter.noteFailure(source.id)
      null
    }
  }

  fun similarity(
    left: String,
    right: String,
  ): Double {
    val a = normalize(left)
    val b = normalize(right)
    if (a.isEmpty() || b.isEmpty()) return 0.0
    if (a == b) return 1.0
    if (a.contains(b) || b.contains(a)) return 0.9
    return dice(a, b)
  }

  private fun displayTitle(series: Series): String =
    seriesMetadataRepository.findByIdOrNull(series.id)?.title?.takeIf { it.isNotBlank() } ?: series.name

  private fun normalize(value: String): String = value.lowercase().filter { it.isLetterOrDigit() }

  private fun dice(
    a: String,
    b: String,
  ): Double {
    if (a.length < 2 || b.length < 2) return 0.0
    val left = a.windowed(2).toSet()
    val right = b.windowed(2).toSet()
    val overlap = left.intersect(right).size
    return 2.0 * overlap / (left.size + right.size)
  }

  private class JobState(
    val job: LibraryJob,
  ) {
    var processed: Int = 0
    var created: Int = 0
    var failed: Int = 0
    var skipped: Int = 0
    var status: String = "QUEUED"

    fun toDto() =
      ScrapeMatchJob(
        id = job.id,
        libraryId = job.libraryId,
        total = job.total,
        processed = processed,
        created = created,
        failed = failed,
        status = status,
        message = if (status == "DONE") "created=$created skipped=$skipped failed=$failed" else null,
      )
  }

  private class LibraryJob(
    val id: String,
    val libraryId: String,
    val total: Int,
  )

  companion object {
    const val MIN_THRESHOLD = 0.6
    const val AUTO_THRESHOLD = 0.85
    private const val SEARCH_LIMIT = 5
  }
}
