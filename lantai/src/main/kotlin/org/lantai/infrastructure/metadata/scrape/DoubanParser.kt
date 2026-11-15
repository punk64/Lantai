package org.lantai.infrastructure.metadata.scrape

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.lantai.domain.model.ScrapeCandidate

internal const val DOUBAN_SOURCE_ID = "douban"

private const val DATA_MARKER = "window.__DATA__ = "
private val TITLE_SPLIT = Regex("""\s*[:：]\s+""")
private val YEAR_REGEX = Regex("""^\d{4}(-\d{1,2}(-\d{1,2})?)?$""")
private val HAS_DIGIT = Regex("""\d""")

object DoubanParser {
  private val mapper = ObjectMapper()

  fun parse(
    html: String,
    limit: Int,
  ): List<ScrapeCandidate> {
    val start = html.indexOf(DATA_MARKER)
    if (start < 0) return emptyList()
    val root: JsonNode =
      try {
        mapper.readTree(html.substring(start + DATA_MARKER.length))
      } catch (e: Exception) {
        return emptyList()
      }
    val items = root.path("items")
    if (!items.isArray) return emptyList()

    return items
      .mapNotNull { node ->
        if (node.path("tpl_name").asText() != "search_subject") return@mapNotNull null
        toCandidate(node)
      }.take(limit)
  }

  private fun toCandidate(node: JsonNode): ScrapeCandidate? {
    val rawTitle = node.path("title").asText().trim()
    if (rawTitle.isEmpty()) return null
    val split = splitTitle(rawTitle)
    val parsed = parseAbstract(node.path("abstract").asText())
    val rating = node.path("rating")

    return ScrapeCandidate(
      source = DOUBAN_SOURCE_ID,
      sourceId = node.path("id").asText(),
      title = split.first,
      subtitle = split.second,
      authors = parsed.authors,
      publisher = parsed.publisher,
      releaseDate = parsed.releaseDate,
      coverUrl = node.path("cover_url").asText(),
      rating = rating.path("value").takeIf { it.isNumber }?.asDouble(),
      ratingCount = rating.path("count").takeIf { it.isNumber }?.asInt(),
      url = node.path("url").asText(),
    )
  }

  private fun splitTitle(raw: String): Pair<String, String> {
    val match = TITLE_SPLIT.find(raw) ?: return raw to ""
    val title = raw.substring(0, match.range.first).trim()
    val subtitle = raw.substring(match.range.last + 1).trim()
    return if (title.isEmpty()) raw to "" else title to subtitle
  }

  private fun parseAbstract(raw: String): ParsedAbstract {
    val parts = raw.split("/").map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.isEmpty()) return ParsedAbstract()

    var last = parts.lastIndex
    var year = ""
    if (last >= 0) {
      val tail = parts[last]
      when {
        YEAR_REGEX.matches(tail) -> {
          year = tail
          last--
        }
        HAS_DIGIT.containsMatchIn(tail) -> {
          last--
          if (last >= 0 && YEAR_REGEX.matches(parts[last])) {
            year = parts[last]
            last--
          }
        }
      }
    }
    if (last < 0) return ParsedAbstract(releaseDate = year)

    val head = parts.subList(0, last + 1)
    return ParsedAbstract(
      authors = head.dropLast(1),
      publisher = head.last(),
      releaseDate = year,
    )
  }

  private data class ParsedAbstract(
    val authors: List<String> = emptyList(),
    val publisher: String = "",
    val releaseDate: String = "",
  )
}
