package org.lantai.infrastructure.metadata.scrape

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.LocalDate

object ScrapePayloadParser {
  fun parse(objectMapper: ObjectMapper, raw: String): ScrapeValues {
    val root = objectMapper.readTree(raw)
    val node = if (root.has("metadata") && root.get("metadata").isObject) root.get("metadata") else root
    return ScrapeValues(
      title = text(node, "title"),
      summary = text(node, "summary").ifBlank { text(node, "description") },
      publisher = text(node, "publisher"),
      language = text(node, "language"),
      ageRating = text(node, "ageRating").toIntOrNull(),
      genres = list(node, "genres").ifEmpty { list(node, "categories") },
      authors = list(node, "authors").ifEmpty { list(node, "author") },
      releaseDate = date(text(node, "releaseDate").ifBlank { text(node, "publishDate") }),
      isbn = text(node, "isbn"),
      tags = list(node, "tags"),
      coverUrl = text(node, "coverUrl").ifBlank { text(node, "cover") },
    )
  }

  fun parseBooks(objectMapper: ObjectMapper, raw: String): List<ScrapeBookChoice> {
    val root = objectMapper.readTree(raw)
    val node = root.get("books") ?: return emptyList()
    if (!node.isArray) return emptyList()
    return node.mapNotNull { item ->
      val bookId = text(item, "bookId").ifBlank { text(item, "id") }
      if (bookId.isBlank()) return@mapNotNull null
      ScrapeBookChoice(
        bookId = bookId,
        fields = if (item.has("fields")) list(item, "fields") else DEFAULT_BOOK_FIELDS,
        title = text(item, "title"),
        number = text(item, "number"),
        authors = list(item, "authors").ifEmpty { list(item, "author") },
        releaseDate = text(item, "releaseDate").ifBlank { text(item, "publishDate") },
        isbn = text(item, "isbn"),
        tags = list(item, "tags"),
        summary = text(item, "summary").ifBlank { text(item, "description") },
        publisher = text(item, "publisher"),
        genres = list(item, "genres").ifEmpty { list(item, "categories") },
        language = text(item, "language"),
      )
    }
  }

  private val DEFAULT_BOOK_FIELDS = listOf("title", "number", "authors", "releaseDate", "isbn", "tags", "summary", "publisher", "genres", "language")

  private fun text(node: JsonNode, field: String): String {
    val value = node.get(field) ?: return ""
    return when {
      value.isNull -> ""
      value.isArray -> value.mapNotNull { it.asText().takeIf { text -> text.isNotBlank() } }.joinToString("、")
      else -> value.asText().trim()
    }
  }

  private fun list(node: JsonNode, field: String): List<String> {
    val value = node.get(field) ?: return emptyList()
    return when {
      value.isNull -> emptyList()
      value.isArray -> value.mapNotNull { it.asText().trim().takeIf { text -> text.isNotEmpty() } }
      else -> value.asText().split('、', ',', '/').mapNotNull { it.trim().takeIf { text -> text.isNotEmpty() } }
    }
  }

  private fun date(value: String): LocalDate? {
    if (value.isBlank()) return null
    val trimmed = value.trim().take(10)
    return runCatching { LocalDate.parse(trimmed) }.getOrNull()
  }
}
