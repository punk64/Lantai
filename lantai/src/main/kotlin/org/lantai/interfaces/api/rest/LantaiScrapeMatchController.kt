package org.lantai.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.lantai.domain.model.ScrapeCandidate
import org.lantai.domain.model.ScrapeMatch
import org.lantai.domain.model.SeriesMetadata
import org.lantai.domain.persistence.ScrapeMatchRepository
import org.lantai.domain.persistence.SeriesMetadataRepository
import org.lantai.infrastructure.metadata.scrape.ScrapeApplier
import org.lantai.infrastructure.metadata.scrape.ScrapeApplyResult
import org.lantai.infrastructure.metadata.scrape.ScrapeBackoffException
import org.lantai.infrastructure.metadata.scrape.ScrapeCircuitOpenException
import org.lantai.infrastructure.metadata.scrape.ScrapeException
import org.lantai.infrastructure.metadata.scrape.ScrapeMatchJob
import org.lantai.infrastructure.metadata.scrape.ScrapeMatcher
import org.lantai.infrastructure.metadata.scrape.ScrapeQuotaExceededException
import org.lantai.infrastructure.metadata.scrape.ScrapeRateLimiter
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("api/lantai/v1/scrape", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Lantai Scrape Match")
class LantaiScrapeMatchController(
  private val matcher: ScrapeMatcher,
  private val applier: ScrapeApplier,
  private val matchRepository: ScrapeMatchRepository,
  private val seriesMetadataRepository: SeriesMetadataRepository,
  private val rateLimiter: ScrapeRateLimiter,
) {
  @PostMapping("match/jobs")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Start a background auto-match job for a library")
  fun startJob(
    @RequestBody body: ScrapeMatchJobRequest,
  ): ScrapeMatchJob = guard { matcher.startJob(body.libraryId, body.limit ?: DEFAULT_JOB_LIMIT) }

  @GetMapping("match/jobs")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "List match jobs of this session")
  fun jobs(): List<ScrapeMatchJob> = matcher.jobs()

  @GetMapping("match/jobs/{jobId}")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Poll a match job")
  fun job(
    @PathVariable jobId: String,
  ): ScrapeMatchJob = matcher.job(jobId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Job $jobId does not exist")

  @PostMapping("match/{seriesId}")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Match a single series against enabled sources")
  fun matchSeries(
    @PathVariable seriesId: String,
  ): ScrapeMatchDto = guard {
    val match = matcher.matchSeries(seriesId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No candidate above the threshold for $seriesId")
    match.toDto()
  }

  @GetMapping("matches")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "List match candidates with their current and proposed values")
  fun matches(
    @RequestParam(name = "status", defaultValue = "PENDING") status: String,
    @RequestParam(name = "seriesId", required = false) seriesId: String?,
  ): List<ScrapeMatchDto> {
    if (!seriesId.isNullOrBlank()) return matchRepository.findBySeriesId(seriesId).map { it.toDto() }
    val parsed = runCatching { ScrapeMatch.Status.valueOf(status.uppercase()) }.getOrNull()
      ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown status: $status")
    return matchRepository.findAllByStatus(parsed).map { it.toDto() }
  }

  @PostMapping("matches/{matchId}/apply")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Apply selected fields of a match and keep a rollback snapshot")
  fun apply(
    @PathVariable matchId: String,
    @RequestBody body: ScrapeApplyRequest,
  ): ScrapeApplyResult = guard { applier.apply(matchId, body.fields.toSet()) }

  @PostMapping("matches/{matchId}/skip")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Mark a match as skipped")
  fun skip(
    @PathVariable matchId: String,
  ): ScrapeApplyResult = guard { applier.skip(matchId) }

  @PostMapping("matches/{matchId}/rollback")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Undo an applied match using its snapshot")
  fun rollback(
    @PathVariable matchId: String,
  ): ScrapeApplyResult = guard { applier.rollback(matchId) }

  @PostMapping("sources/{sourceId}/reset")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Clear backoff and circuit state of a source")
  fun resetSource(
    @PathVariable sourceId: String,
  ) {
    rateLimiter.resetCircuit(sourceId)
  }

  private fun <T> guard(block: () -> T): T =
    try {
      block()
    } catch (e: ScrapeQuotaExceededException) {
      throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, e.message, e)
    } catch (e: ScrapeBackoffException) {
      throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, e.message, e)
    } catch (e: ScrapeCircuitOpenException) {
      throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.message, e)
    } catch (e: ScrapeException) {
      throw ResponseStatusException(HttpStatus.BAD_GATEWAY, e.message, e)
    }

  private fun ScrapeMatch.toDto(): ScrapeMatchDto {
    val metadata = seriesMetadataRepository.findByIdOrNull(seriesId)
    return ScrapeMatchDto(
      id = id,
      seriesId = seriesId,
      seriesTitle = seriesTitle,
      source = source,
      sourceId = sourceId,
      score = score,
      autoSelected = score >= ScrapeMatcher.AUTO_THRESHOLD,
      status = status.name,
      candidate = candidate,
      current = metadata.toCurrentValues(),
      appliedFields = appliedFields.sorted(),
      lockedFields = metadata.lockedFields(),
    )
  }

  private fun SeriesMetadata?.toCurrentValues(): ScrapeCurrentValues =
    ScrapeCurrentValues(
      title = this?.title.orEmpty(),
      summary = this?.summary.orEmpty(),
      publisher = this?.publisher.orEmpty(),
      genres = this?.genres?.sorted().orEmpty(),
    )

  private fun SeriesMetadata?.lockedFields(): List<String> =
    when {
      this == null -> emptyList()
      else ->
        buildList {
          if (titleLock) add("title")
          if (summaryLock) add("summary")
          if (publisherLock) add("publisher")
          if (genresLock) add("genres")
        }
    }

  private companion object {
    const val DEFAULT_JOB_LIMIT = 20
  }
}

data class ScrapeMatchJobRequest(
  val libraryId: String,
  val limit: Int? = null,
)

data class ScrapeApplyRequest(
  val fields: List<String> = emptyList(),
)

data class ScrapeCurrentValues(
  val title: String,
  val summary: String,
  val publisher: String,
  val genres: List<String>,
)

data class ScrapeMatchDto(
  val id: String,
  val seriesId: String,
  val seriesTitle: String,
  val source: String,
  val sourceId: String,
  val score: Double,
  val autoSelected: Boolean,
  val status: String,
  val candidate: ScrapeCandidate,
  val current: ScrapeCurrentValues,
  val appliedFields: List<String>,
  val lockedFields: List<String>,
)
