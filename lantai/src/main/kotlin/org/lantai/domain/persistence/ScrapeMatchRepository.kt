package org.lantai.domain.persistence

import org.lantai.domain.model.ScrapeMatch

interface ScrapeMatchRepository {
  fun insert(match: ScrapeMatch)

  fun findByIdOrNull(id: String): ScrapeMatch?

  fun findAllByStatus(status: ScrapeMatch.Status): List<ScrapeMatch>

  fun findBySeriesId(seriesId: String): List<ScrapeMatch>

  fun update(match: ScrapeMatch)

  fun deleteBySeriesId(seriesId: String)

  fun countByStatus(status: ScrapeMatch.Status): Long
}
