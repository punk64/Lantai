package org.lantai.domain.persistence

import org.lantai.domain.model.BookMetadataAggregation

interface BookMetadataAggregationRepository {
  fun findById(seriesId: String): BookMetadataAggregation

  fun findByIdOrNull(seriesId: String): BookMetadataAggregation?

  fun insert(metadata: BookMetadataAggregation)

  fun update(metadata: BookMetadataAggregation)

  fun delete(seriesId: String)

  fun delete(seriesIds: Collection<String>)

  fun count(): Long
}
