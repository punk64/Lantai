package org.lantai.domain.model

data class ScrapeCandidate(
  val source: String,
  val sourceId: String,
  val title: String,
  val subtitle: String = "",
  val authors: List<String> = emptyList(),
  val publisher: String = "",
  val region: String = "",
  val releaseDate: String = "",
  val summary: String = "",
  val coverUrl: String = "",
  val tags: List<String> = emptyList(),
  val rating: Double? = null,
  val ratingCount: Int? = null,
  val url: String = "",
)
