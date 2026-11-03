package org.lantai.infrastructure.metadata.scrape

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.jsoup.Jsoup
import org.lantai.domain.model.ScrapeCandidate
import org.lantai.domain.model.ScrapeSourceConfig
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.time.Duration

class DeclarativeScrapeSource(
  private val config: ScrapeSourceConfig,
  private val mapper: ObjectMapper,
  private val rateLimiter: ScrapeRateLimiter,
  webClientBuilder: WebClient.Builder,
) : ScrapeSource {
  override val id: String = config.id
  override val displayName: String = config.displayName

  private val webClient = webClientBuilder.clone().build()

  override fun isEnabled(): Boolean = config.enabled

  override fun search(
    query: String,
    limit: Int,
  ): List<ScrapeCandidate> {
    if (!config.enabled) throw ScrapeException("Source $id is not enabled")
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return emptyList()
    val capped = limit.coerceIn(1, 50)

    rateLimiter.checkQuota(id)
    rateLimiter.acquire(id)
    val body =
      try {
        fetch(trimmed, capped)
      } catch (e: WebClientResponseException) {
        throw ScrapeException("$displayName responded with ${e.statusCode.value()}", e)
      } catch (e: ScrapeException) {
        throw e
      } catch (e: Exception) {
        throw ScrapeException("$displayName request failed: ${e.message}", e)
      } finally {
        rateLimiter.record(id)
      }

    if (body.isBlank()) return emptyList()
    return when (config.kind) {
      ScrapeSourceConfig.Kind.HTML -> parseHtml(body, capped)
      else -> parseJson(body, capped)
    }
  }

  private fun fetch(
    query: String,
    limit: Int,
  ): String {
    val url = config.baseUrl.trimEnd('/') + fill(config.queryTemplate.orEmpty(), query, limit)
    val request =
      if (config.kind == ScrapeSourceConfig.Kind.GRAPHQL) {
        webClient
          .post()
          .uri(url)
          .headers { headers -> config.headers.forEach { (k, v) -> headers.add(k, v) } }
          .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
          .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
          .bodyValue(
            mapOf(
              "query" to config.queryTemplate.orEmpty(),
              "variables" to mapOf("search" to query, "q" to query, "perPage" to limit, "limit" to limit),
            ),
          )
      } else {
        webClient
          .get()
          .uri(url)
          .headers { headers -> config.headers.forEach { (k, v) -> headers.add(k, v) } }
          .header(
            HttpHeaders.ACCEPT,
            if (config.kind == ScrapeSourceConfig.Kind.HTML) MediaType.TEXT_HTML_VALUE else MediaType.APPLICATION_JSON_VALUE,
          )
          .header(HttpHeaders.USER_AGENT, config.headers["User-Agent"] ?: DEFAULT_UA)
      }
    return request.retrieve().bodyToMono(String::class.java).block(Duration.ofSeconds(25)).orEmpty()
  }

  private fun fill(
    template: String,
    query: String,
    limit: Int,
  ): String =
    template
      .replace("{q}", urlEncode(query))
      .replace("{limit}", limit.toString())

  private fun parseJson(
    body: String,
    limit: Int,
  ): List<ScrapeCandidate> {
    val root =
      runCatching { mapper.readTree(body) }.getOrElse { return emptyList() }
    val array = nodeAt(root, config.resultPath)
    if (array == null || !array.isArray) return emptyList()
    return array.mapNotNull { node ->
      val title = text(node, FIELD_TITLE)
      if (title.isBlank()) return@mapNotNull null
      ScrapeCandidate(
        source = id,
        sourceId = text(node, FIELD_SOURCE_ID).ifBlank { text(node, FIELD_URL) }.ifBlank { title },
        title = title,
        subtitle = text(node, FIELD_SUBTITLE),
        authors = list(node, FIELD_AUTHORS),
        publisher = text(node, FIELD_PUBLISHER),
        releaseDate = text(node, FIELD_RELEASE_DATE),
        summary = text(node, FIELD_SUMMARY),
        coverUrl = text(node, FIELD_COVER),
        tags = list(node, FIELD_TAGS),
        rating = text(node, FIELD_RATING).toDoubleOrNull(),
        ratingCount = text(node, FIELD_RATING_COUNT).toIntOrNull(),
        url = text(node, FIELD_URL),
      )
    }.take(limit)
  }

  private fun parseHtml(
    body: String,
    limit: Int,
  ): List<ScrapeCandidate> {
    val doc = Jsoup.parse(body)
    val nodes = if (config.resultPath.isBlank()) doc.body().children() else doc.select(config.resultPath)
    if (nodes.isEmpty()) return emptyList()
    return nodes.mapNotNull { el ->
      val title = htmlValue(el, FIELD_TITLE)
      if (title.isBlank()) return@mapNotNull null
      ScrapeCandidate(
        source = id,
        sourceId = htmlValue(el, FIELD_SOURCE_ID).ifBlank { htmlValue(el, FIELD_URL) }.ifBlank { title },
        title = title,
        subtitle = htmlValue(el, FIELD_SUBTITLE),
        authors = htmlList(el, FIELD_AUTHORS),
        publisher = htmlValue(el, FIELD_PUBLISHER),
        releaseDate = htmlValue(el, FIELD_RELEASE_DATE),
        summary = htmlValue(el, FIELD_SUMMARY),
        coverUrl = htmlValue(el, FIELD_COVER),
        tags = htmlList(el, FIELD_TAGS),
        rating = htmlValue(el, FIELD_RATING).toDoubleOrNull(),
        url = htmlValue(el, FIELD_URL),
      )
    }.take(limit)
  }

  private fun nodeAt(
    root: JsonNode,
    path: String,
  ): JsonNode? {
    if (path.isBlank()) return root
    var current: JsonNode = root
    for (segment in path.split('.')) {
      val key = segment.trim()
      if (key.isEmpty()) continue
      current = current.path(key)
      if (current.isMissingNode || current.isNull) return null
    }
    return current
  }

  private fun text(
    node: JsonNode,
    field: String,
  ): String {
    val path = config.fieldMap[field]?.trim().orEmpty()
    if (path.isEmpty()) return ""
    val current = nodeAt(node, path.removeSuffix("[]")) ?: return ""
    return when {
      current.isValueNode -> current.asText().trim()
      current.isArray -> current.joinToString(", ") { it.asText().trim() }.trim()
      else -> ""
    }
  }

  private fun list(
    node: JsonNode,
    field: String,
  ): List<String> {
    val path = config.fieldMap[field]?.trim().orEmpty()
    if (path.isEmpty()) return emptyList()
    val current = nodeAt(node, path.removeSuffix("[]")) ?: return emptyList()
    return when {
      current.isArray -> current.mapNotNull { it.asText().trim().ifBlank { null } }
      current.isValueNode -> current.asText().split(',', '/').mapNotNull { it.trim().ifBlank { null } }
      else -> emptyList()
    }
  }

  private fun htmlValue(
    element: org.jsoup.nodes.Element,
    field: String,
  ): String = htmlList(element, field).firstOrNull().orEmpty()

  private fun htmlList(
    element: org.jsoup.nodes.Element,
    field: String,
  ): List<String> {
    val spec = config.fieldMap[field]?.trim().orEmpty()
    if (spec.isEmpty()) return emptyList()
    val parts = spec.split('@', limit = 2)
    val selector = parts[0].trim().ifBlank { ":root" }
    val attribute = parts.getOrNull(1)?.trim()
    val targets =
      if (selector == ":root") listOf(element)
      else element.select(selector).toList()
    if (targets.isEmpty()) return emptyList()
    return targets.mapNotNull { target ->
      val raw = if (attribute.isNullOrBlank() || attribute == "text") target.text() else target.attr(attribute)
      raw.trim().ifBlank { null }
    }
  }

  private fun urlEncode(raw: String): String =
    java.net.URLEncoder.encode(raw, Charsets.UTF_8).replace("+", "%20")

  companion object {
    private const val DEFAULT_UA = "Mozilla/5.0 (compatible; Lantai/0.1)"
    private const val FIELD_TITLE = "title"
    private const val FIELD_SUBTITLE = "subtitle"
    private const val FIELD_SOURCE_ID = "sourceId"
    private const val FIELD_AUTHORS = "authors"
    private const val FIELD_PUBLISHER = "publisher"
    private const val FIELD_RELEASE_DATE = "releaseDate"
    private const val FIELD_SUMMARY = "summary"
    private const val FIELD_COVER = "coverUrl"
    private const val FIELD_TAGS = "tags"
    private const val FIELD_RATING = "rating"
    private const val FIELD_RATING_COUNT = "ratingCount"
    private const val FIELD_URL = "url"
  }
}
