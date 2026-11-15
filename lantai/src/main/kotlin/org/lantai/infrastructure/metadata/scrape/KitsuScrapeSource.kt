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

private const val SOURCE_ID = "kitsu"
private const val DEFAULT_BASE_URL = "https://kitsu.io"

@Service
class KitsuScrapeSource(
  private val properties: ScrapeProperties,
  private val rateLimiter: ScrapeRateLimiter,
  private val mapper: ObjectMapper,
  webClientBuilder: WebClient.Builder,
) : ScrapeSource {
  override val id: String = SOURCE_ID
  override val displayName: String = "Kitsu"

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
          .get()
          .uri { it.path("/api/edge/manga").queryParam("filter[text]", trimmed).queryParam("page[limit]", capped).build() }
          .header(HttpHeaders.ACCEPT, "application/vnd.api+json")
          .header(HttpHeaders.USER_AGENT, settings.userAgent.ifBlank { "Lantai/0.5.1" })
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
    val data =
      try {
        mapper.readTree(body).path("data")
      } catch (e: Exception) {
        return emptyList()
      }
    if (!data.isArray) return emptyList()
    return data.mapNotNull { toCandidate(it) }.take(capped)
  }

  private fun toCandidate(node: JsonNode): ScrapeCandidate? {
    val attrs = node.path("attributes")
    val title = attrs.path("canonicalTitle").asText().ifBlank { attrs.path("titles").path("en").asText() }
    if (title.isBlank()) return null
    val titles = attrs.path("titles")
    val native = titles.path("ja_jp").asText().ifBlank { titles.path("ja").asText() }
    val score = attrs.path("averageRating").asText().toDoubleOrNull()
    return ScrapeCandidate(
      source = SOURCE_ID,
      sourceId = node.path("id").asText(),
      title = title,
      subtitle = native,
      releaseDate = attrs.path("startDate").asText(),
      summary = attrs.path("synopsis").asText(),
      coverUrl = attrs.path("posterImage").path("large").asText(),
      rating = score?.let { it / 10.0 },
      url = "https://kitsu.io/manga/${attrs.path("slug").asText()}",
    )
  }
}
