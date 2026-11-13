package org.lantai.interfaces.api.rest

import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.lantai.domain.model.ScrapeCandidate
import org.lantai.infrastructure.metadata.scrape.ScrapeException
import org.lantai.infrastructure.metadata.scrape.ScrapeProperties
import org.lantai.infrastructure.metadata.scrape.ScrapeQuotaExceededException
import org.lantai.infrastructure.metadata.scrape.ScrapeRateLimiter
import org.lantai.infrastructure.metadata.scrape.ScrapeSourceRegistry
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

private val logger = KotlinLogging.logger {}

@RestController
@RequestMapping("api/lantai/v1/scrape", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Lantai Scrape")
class LantaiScrapeController(
  private val registry: ScrapeSourceRegistry,
  private val rateLimiter: ScrapeRateLimiter,
  private val properties: ScrapeProperties,
) {
  @GetMapping("sources")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "List online metadata sources and their daily usage")
  fun listSources(): List<ScrapeSourceInfoDto> =
    registry.list().map {
      ScrapeSourceInfoDto(
        id = it.id,
        displayName = it.displayName,
        enabled = it.enabled,
        usageToday = rateLimiter.usage(it.id),
        dailyQuota = if (it.dailyQuota > 0) it.dailyQuota else rateLimiter.quota(it.id),
        kind = it.kind.name,
        builtin = it.builtin,
        baseUrl = it.baseUrl,
        language = properties.source(it.id).language,
      )
    }

  @GetMapping("search")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Search metadata candidates from one or all enabled sources")
  fun search(
    @RequestParam(name = "q") query: String,
    @RequestParam(name = "source", required = false) sourceId: String?,
    @RequestParam(name = "limit", defaultValue = "10") limit: Int,
  ): List<ScrapeCandidateDto> {
    val selected =
      if (sourceId.isNullOrBlank()) {
        registry.enabled()
      } else {
        registry.find(sourceId)?.let { listOf(it) } ?: emptyList()
      }
    if (selected.isEmpty()) throw ResponseStatusException(HttpStatus.NOT_FOUND, "No enabled source matched")
    if (query.isBlank()) return emptyList()

    val results = mutableListOf<ScrapeCandidateDto>()
    var lastError: ScrapeException? = null

    selected.forEach { source ->
      rateLimiter.cached(source.id, query, limit)?.let {
        results += it.map { candidate -> candidate.toDto() }
        return@forEach
      }
      try {
        val found = source.search(query, limit)
        rateLimiter.cache(source.id, query, limit, found)
        results += found.map { it.toDto() }
      } catch (e: ScrapeQuotaExceededException) {
        throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, e.message)
      } catch (e: ScrapeException) {
        rateLimiter.noteFailure(source.id)
        logger.warn(e) { "Scrape source ${source.id} failed" }
        lastError = e
      }
    }

    if (results.isEmpty()) lastError?.let { throw ResponseStatusException(HttpStatus.BAD_GATEWAY, it.message) }

    return results
  }
}

data class ScrapeSourceInfoDto(
  val id: String,
  val displayName: String,
  val enabled: Boolean,
  val usageToday: Int,
  val dailyQuota: Int,
  val kind: String = "BUILTIN",
  val builtin: Boolean = false,
  val baseUrl: String = "",
  val language: String = "",
)

data class ScrapeCandidateDto(
  val source: String,
  val sourceId: String,
  val title: String,
  val subtitle: String,
  val authors: List<String>,
  val publisher: String,
  val releaseDate: String,
  val summary: String,
  val coverUrl: String,
  val tags: List<String>,
  val rating: Double?,
  val ratingCount: Int?,
  val url: String,
)

private fun ScrapeCandidate.toDto() =
  ScrapeCandidateDto(
    source = source,
    sourceId = sourceId,
    title = title,
    subtitle = subtitle,
    authors = authors,
    publisher = publisher,
    releaseDate = releaseDate,
    summary = summary,
    coverUrl = coverUrl,
    tags = tags,
    rating = rating,
    ratingCount = ratingCount,
    url = url,
  )
