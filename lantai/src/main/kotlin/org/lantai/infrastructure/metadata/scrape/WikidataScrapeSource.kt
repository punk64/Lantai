package org.lantai.infrastructure.metadata.scrape

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.lantai.domain.model.ScrapeCandidate
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.time.Duration

private const val SOURCE_ID = "wikidata"
private const val DEFAULT_BASE_URL = "https://www.wikidata.org"
private const val USER_AGENT = "Lantai/0.5.1 (manga metadata lookup)"

private val MANGA_HINT = Regex("(漫畫|漫画|comic|manga|manhwa|manhua|webtoon)", RegexOption.IGNORE_CASE)

private val COUNTRY_BY_QID =
  mapOf(
    "Q17" to "JP",
    "Q884" to "KR",
    "Q148" to "CN",
    "Q865" to "TW",
    "Q8646" to "HK",
    "Q30" to "US",
    "Q145" to "GB",
    "Q142" to "FR",
    "Q183" to "DE",
    "Q38" to "IT",
    "Q29" to "ES",
    "Q16" to "CA",
    "Q408" to "AU",
    "Q159" to "RU",
    "Q928" to "PH",
    "Q711" to "MN",
    "Q252" to "ID",
    "Q881" to "VN",
    "Q869" to "TH",
    "Q833" to "MY",
    "Q334" to "SG",
    "Q43" to "TR",
    "Q801" to "IL",
    "Q155" to "BR",
    "Q96" to "MX",
    "Q414" to "AR",
    "Q36" to "PL",
    "Q55" to "NL",
    "Q39" to "CH",
    "Q34" to "SE",
    "Q35" to "DK",
    "Q20" to "NO",
    "Q33" to "FI",
    "Q213" to "CZ",
    "Q214" to "SK",
    "Q28" to "HU",
    "Q218" to "RO",
    "Q212" to "UA",
  )

@Service
class WikidataScrapeSource(
  private val properties: ScrapeProperties,
  private val rateLimiter: ScrapeRateLimiter,
  private val mapper: ObjectMapper,
  webClientBuilder: WebClient.Builder,
) : ScrapeSource {
  override val id: String = SOURCE_ID
  override val displayName: String = "Wikidata"

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
    val ids =
      try {
        val body = fetch("/w/api.php?action=wbsearchentities&search=${encode(trimmed)}&language=zh&uselang=zh&format=json&limit=$capped&type=item")
        val hits = mapper.readTree(body).path("search")
        if (!hits.isArray) return emptyList()
        val compact = hits.mapNotNull { node ->
          val qid = node.path("id").asText()
          if (qid.isBlank()) null else qid to (node.path("description").asText() + " " + node.path("label").asText())
        }
        val filtered = compact.filter { MANGA_HINT.containsMatchIn(it.second) }
        (if (filtered.isNotEmpty()) filtered else compact).map { it.first }.distinct().take(capped)
      } finally {
        rateLimiter.record(id)
      }

    if (ids.isEmpty()) return emptyList()
    rateLimiter.acquire(id)
    val entities =
      try {
        val body = fetch("/w/api.php?action=wbgetentities&ids=${ids.joinToString("|")}&props=claims%7Clabels&languages=zh%7Czh-hans%7Czh-hant%7Cja%7Cen&format=json")
        mapper.readTree(body).path("entities")
      } catch (e: Exception) {
        return emptyList()
      } finally {
        rateLimiter.record(id)
      }

    return ids.mapNotNull { qid -> entities.path(qid).takeIf { !it.isMissingNode }?.let { toCandidate(qid, it) } }
  }

  private fun fetch(path: String): String =
    try {
      webClient
        .get()
        .uri(path)
        .header(HttpHeaders.USER_AGENT, settings.userAgent.ifBlank { USER_AGENT })
        .header(HttpHeaders.ACCEPT, "application/json")
        .retrieve()
        .bodyToMono(String::class.java)
        .block(Duration.ofSeconds(20))
        .orEmpty()
    } catch (e: WebClientResponseException) {
      throw ScrapeException("$displayName responded with ${e.statusCode.value()}", e)
    } catch (e: ScrapeException) {
      throw e
    } catch (e: Exception) {
      throw ScrapeException("$displayName request failed: ${e.message}", e)
    }

  private fun toCandidate(
    qid: String,
    node: JsonNode,
  ): ScrapeCandidate? {
    val labels = node.path("labels")
    val zh = labelOf(labels, "zh", "zh-hans", "zh-hant")
    val ja = labelOf(labels, "ja")
    val en = labelOf(labels, "en")
    val title = zh.ifBlank { ja }.ifBlank { en }
    if (title.isBlank()) return null
    val subtitle = when {
      title == ja -> en
      zh.isNotBlank() && ja.isNotBlank() -> ja
      else -> en
    }
    val claims = node.path("claims")
    return ScrapeCandidate(
      source = SOURCE_ID,
      sourceId = qid,
      title = title,
      subtitle = subtitle,
      region = countryOf(claims),
      releaseDate = firstDate(claims.path("P577")),
      url = "https://www.wikidata.org/wiki/$qid",
    )
  }

  private fun labelOf(
    labels: JsonNode,
    vararg langs: String,
  ): String = langs.asSequence().map { labels.path(it).path("value").asText() }.firstOrNull { it.isNotBlank() }.orEmpty()

  private fun countryOf(claims: JsonNode): String {
    val first = claims.path("P495").firstOrNull() ?: return ""
    val qid = first.path("mainsnak").path("datavalue").path("value").path("id").asText()
    return COUNTRY_BY_QID[qid].orEmpty()
  }

  private fun firstDate(claims: JsonNode): String {
    val raw =
      claims
        .filter { it.path("mainsnak").path("datavalue").path("type").asText() == "time" }
        .map { it.path("mainsnak").path("datavalue").path("value").path("time").asText() }
        .filter { it.length >= 11 && it[0] == '+' }
        .minOrNull()
        ?: return ""
    val year = raw.substring(1, 5)
    val month = raw.substring(6, 8)
    val day = raw.substring(9, 11)
    return if (month == "00") year else if (day == "00") "$year-$month" else "$year-$month-$day"
  }

  private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
}
