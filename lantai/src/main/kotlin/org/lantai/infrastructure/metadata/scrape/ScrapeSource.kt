package org.lantai.infrastructure.metadata.scrape

import org.lantai.domain.model.ScrapeCandidate

interface ScrapeSource {
  val id: String
  val displayName: String

  fun isEnabled(): Boolean

  fun search(
    query: String,
    limit: Int,
  ): List<ScrapeCandidate>
}

class ScrapeException(
  message: String,
  cause: Throwable? = null,
) : RuntimeException(message, cause)

class ScrapeQuotaExceededException(
  sourceId: String,
) : RuntimeException("Daily quota exceeded for source $sourceId")
