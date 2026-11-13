package org.lantai.infrastructure.metadata.scrape

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.lantai.domain.model.ScrapeCandidate
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.time.Duration

private const val SOURCE_ID = "mangaupdates"
private const val DEFAULT_BASE_URL = "https://api.mangaupdates.com"

@Service
class MangaUpdatesScrapeSource(
  private val properties: ScrapeProperties,
  private val rateLimiter: ScrapeRateLimiter,
  private val mapper: ObjectMapper,
  webClientBuilder: WebClient.Builder,
) : ScrapeSource {
  override val id: String = SOURCE_ID
  override val displayName: String = "MangaUpdates"

  private val settings = properties.source(SOURCE_ID)
  private val webClient = webClientBuilder.baseUrl(settings.baseUrl.ifBlank { DEFAULT_BASE_URL }).build()

  override fun isEnabled(): Boolean = settings.enabled

  override fun search(
    query: String,
    limit: Int,
  ): List<ScrapeCandidate> {
    if (!isEnabled()) throw ScrapeException("Source $id is not enabled")
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return emptyList()
    val capped = limit.coerceIn(1, 20)

    rateLimiter.checkQuota(id)
    rateLimiter.acquire(id)
    val body =
      try {
        webClient
          .post()
          .uri("/v1/series/search")
          .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
          .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
          .header(HttpHeaders.USER_AGENT, settings.userAgent.ifBlank { "Lantai/0.5.1" })
          .bodyValue(mapOf("search" to trimmed, "perpage" to capped, "page" to 1))
          .retrieve()
          .bodyToMono(String::class.java)
          .block(Duration.ofSeconds(20))
          .orEmpty()
      } catch (e: WebClientResponseException) {
        throw ScrapeException("$displayName responded with ${e.statusCode.value()}", e)
      } catch (e: Exception) {
        throw ScrapeException("$displayName request failed: ${e.message}", e)
      } finally {
        rateLimiter.record(id)
      }

    if (body.isBlank()) return emptyList()
    val results =
      try {
        mapper.readTree(body).path("results")
      } catch (e: Exception) {
        return emptyList()
      }
    if (!results.isArray) return emptyList()
    return results.mapNotNull { toCandidate(it.path("record")) }.take(capped)
  }

  private fun toCandidate(record: JsonNode): ScrapeCandidate? {
    val title = record.path("title").asText()
    if (title.isBlank()) return null
    val authors = record.path("authors").mapNotNull { it.path("name").asText().ifBlank { null } }
    val publishers = record.path("publishers").mapNotNull { it.path("publisher_name").asText().ifBlank { null } }
    val genres = record.path("genres").mapNotNull { it.path("genre").asText().ifBlank { null } }
    return ScrapeCandidate(
      source = SOURCE_ID,
      sourceId = record.path("series_id").asText(),
      title = title,
      authors = authors,
      publisher = publishers.firstOrNull().orEmpty(),
      releaseDate = record.path("year").asText().takeIf { it.isNotBlank() && it != "0" }.orEmpty(),
      summary = record.path("description").asText(),
      coverUrl = record.path("image").path("url").asText(),
      tags = genres,
      rating = record.path("bayesian_rating").asText().toDoubleOrNull(),
      ratingCount = record.path("rating_votes").asInt().takeIf { it > 0 },
      url = record.path("url").asText(),
    )
  }
}
