package org.lantai.domain.persistence

import org.lantai.domain.model.HistoricalEvent

interface HistoricalEventRepository {
  fun insert(event: HistoricalEvent)
}
