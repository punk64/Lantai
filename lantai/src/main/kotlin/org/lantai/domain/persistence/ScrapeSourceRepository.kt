package org.lantai.domain.persistence

import org.lantai.domain.model.ScrapeSourceConfig

interface ScrapeSourceRepository {
  fun findAll(): List<ScrapeSourceConfig>

  fun findByIdOrNull(id: String): ScrapeSourceConfig?

  fun insert(config: ScrapeSourceConfig)

  fun update(config: ScrapeSourceConfig)

  fun delete(id: String)

  fun setEnabled(id: String, enabled: Boolean)
}
