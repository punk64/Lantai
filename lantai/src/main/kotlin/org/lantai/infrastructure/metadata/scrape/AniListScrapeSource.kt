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

private const val SOURCE_ID = "anilist"
private const val DEFAULT_BASE_URL = "https://graphql.anilist.co"
private val HTML_BREAK = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
private val HTML_TAG = Regex("<[^>]+>")

private val SEARCH_QUERY =
  """
  query (${'$'}search: String, ${'$'}perPage: Int) {
    Page(page: 1, perPage: ${'$'}perPage) {
      media(search: ${'$'}search, type: MANGA, sort: SEARCH_MATCH) {
        id
        title { native romaji english }
        description
        startDate { year month day }
        coverImage { large }
        genres
        averageScore
        countryOfOrigin
        siteUrl
        staff(perPage: 3) { nodes { name { full } } }
      }
    }
  }
  """.trimIndent()

@Service
class AniListScrapeSource(
  private val properties: ScrapeProperties,
  private val rateLimiter: ScrapeRateLimiter,
  private val mapper: ObjectMapper,
  webClientBuilder: WebClient.Builder,
) : ScrapeSource {
  override val id: String = SOURCE_ID
  override val displayName: String = "AniList"

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
    val capped = limit.coerceIn(1, 30)

    rateLimiter.checkQuota(id)
    rateLimiter.acquire(id)
    val body =
      try {
        webClient
          .post()
          .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
          .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
          .bodyValue(mapOf("query" to SEARCH_QUERY, "variables" to mapOf("search" to trimmed, "perPage" to capped)))
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
    val root: JsonNode =
      try {
        mapper.readTree(body)
      } catch (e: Exception) {
        return emptyList()
      }
    val media = root.path("data").path("Page").path("media")
    if (!media.isArray) return emptyList()

    return media.mapNotNull { toCandidate(it) }.take(capped)
  }

  private fun toCandidate(node: JsonNode): ScrapeCandidate? {
    val id = node.path("id").asLong()
    if (id <= 0) return null

    val titles = node.path("title")
    val native = titles.path("native").asText()
    val romaji = titles.path("romaji").asText()
    val english = titles.path("english").asText()
    val title = native.ifBlank { romaji }.ifBlank { english }
    if (title.isBlank()) return null

    val score = node.path("averageScore").takeIf { it.isNumber }?.asInt()
    val authors =
      node
        .path("staff")
        .path("nodes")
        .mapNotNull { it.path("name").path("full").asText().ifBlank { null } }

    return ScrapeCandidate(
      source = SOURCE_ID,
      sourceId = id.toString(),
      title = title,
      subtitle = if (native.isNotBlank() && romaji.isNotBlank()) romaji else "",
      authors = authors,
      releaseDate = formatDate(node.path("startDate")),
      summary = stripHtml(node.path("description").asText()),
      coverUrl = node.path("coverImage").path("large").asText(),
      region = node.path("countryOfOrigin").asText().trim().uppercase(),
      tags = node.path("genres").mapNotNull { it.asText().ifBlank { null } },
      rating = score?.let { it / 10.0 },
      url = node.path("siteUrl").asText(),
    )
  }

  private fun formatDate(node: JsonNode): String {
    val year = node.path("year").takeIf { it.isNumber }?.asInt() ?: return ""
    val month = node.path("month").takeIf { it.isNumber }?.asInt() ?: return year.toString()
    val day = node.path("day").takeIf { it.isNumber }?.asInt() ?: return "%04d-%02d".format(year, month)
    return "%04d-%02d-%02d".format(year, month, day)
  }

  private fun stripHtml(raw: String): String =
    raw
      .replace(HTML_BREAK, "\n")
      .replace(HTML_TAG, "")
      .replace("&amp;", "&")
      .replace("&quot;", "\"")
      .replace("&#039;", "'")
      .replace("&lt;", "<")
      .replace("&gt;", ">")
      .trim()
}
