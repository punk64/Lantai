package org.lantai.infrastructure.metadata.scrape

import org.lantai.domain.model.ScrapeCandidate
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.time.Duration

private const val DEFAULT_BASE_URL = "https://book.douban.com"
private const val DEFAULT_USER_AGENT =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

@Service
class DoubanScrapeSource(
  private val properties: ScrapeProperties,
  private val rateLimiter: ScrapeRateLimiter,
  webClientBuilder: WebClient.Builder,
) : ScrapeSource {
  override val id: String = DOUBAN_SOURCE_ID
  override val displayName: String = "豆瓣读书"

  private val settings = properties.source(DOUBAN_SOURCE_ID)
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
    val html =
      try {
        webClient
          .get()
          .uri {
            it
              .path("/subject_search")
              .queryParam("search_text", trimmed)
              .queryParam("cat", "1001")
              .build()
          }.headers { headers ->
            headers.set(HttpHeaders.USER_AGENT, settings.userAgent.ifBlank { DEFAULT_USER_AGENT })
            headers.set(HttpHeaders.ACCEPT_LANGUAGE, "zh-CN,zh;q=0.9")
            headers.set(HttpHeaders.ACCEPT, "text/html,application/xhtml+xml")
            if (settings.cookie.isNotBlank()) headers.set(HttpHeaders.COOKIE, settings.cookie)
          }.retrieve()
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

    return DoubanParser.parse(html, capped)
  }
}
