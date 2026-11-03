package org.lantai.infrastructure.metadata.scrape

import java.time.LocalDate

enum class ScrapeTarget {
  SERIES,
  BOOK,
  COVER,
}

enum class ScrapeDecision {
  FILL,
  SAME,
  CONFLICT,
  LOCKED,
  EMPTY,
}

data class ScrapeValues(
  val title: String = "",
  val summary: String = "",
  val publisher: String = "",
  val language: String = "",
  val ageRating: Int? = null,
  val genres: List<String> = emptyList(),
  val authors: List<String> = emptyList(),
  val releaseDate: LocalDate? = null,
  val isbn: String = "",
  val tags: List<String> = emptyList(),
  val coverUrl: String = "",
)

data class ScrapeFieldChange(
  val field: String,
  val target: ScrapeTarget,
  val current: String,
  val embedded: String,
  val candidate: String,
  val chosen: String,
  val decision: ScrapeDecision,
)

data class ScrapeDiff(
  val fields: List<ScrapeFieldChange> = emptyList(),
) {
  val conflicts: Int get() = fields.count { it.decision == ScrapeDecision.CONFLICT }
  val fills: Int get() = fields.count { it.decision == ScrapeDecision.FILL }
  val hasChanges: Boolean get() = fields.any { it.decision == ScrapeDecision.FILL || it.decision == ScrapeDecision.CONFLICT }

  fun pendingConflicts(): List<ScrapeFieldChange> = fields.filter { it.decision == ScrapeDecision.CONFLICT }
}

data class EmbeddedBook(
  val bookId: String,
  val values: ScrapeValues,
)

data class EmbeddedRead(
  val series: ScrapeValues = ScrapeValues(),
  val books: List<EmbeddedBook> = emptyList(),
  val hasCoverFile: Boolean = false,
) {
  fun firstBookValues(): ScrapeValues =
    books
      .map { it.values }
      .firstOrNull { it.authors.isNotEmpty() || it.releaseDate != null || it.isbn.isNotBlank() }
      ?: books.firstOrNull()?.values
      ?: ScrapeValues()
}
