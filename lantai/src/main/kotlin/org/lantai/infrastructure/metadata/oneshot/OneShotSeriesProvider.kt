package org.lantai.infrastructure.metadata.oneshot

import org.lantai.domain.model.Library
import org.lantai.domain.model.MetadataPatchTarget
import org.lantai.domain.model.Series
import org.lantai.domain.model.SeriesMetadata
import org.lantai.domain.model.SeriesMetadataPatch
import org.lantai.domain.persistence.BookMetadataRepository
import org.lantai.domain.persistence.BookRepository
import org.lantai.infrastructure.metadata.SeriesMetadataProvider
import org.springframework.stereotype.Service

@Service
class OneShotSeriesProvider(
  private val bookRepository: BookRepository,
  private val bookMetadataRepository: BookMetadataRepository,
) : SeriesMetadataProvider {
  override fun getSeriesMetadata(series: Series): SeriesMetadataPatch? {
    if (!series.oneshot) return null
    val bookMetadata = bookMetadataRepository.findById(bookRepository.findAllIdsBySeriesId(series.id).first())
    return SeriesMetadataPatch(
      bookMetadata.title,
      bookMetadata.title,
      SeriesMetadata.Status.ENDED,
      bookMetadata.summary,
      null,
      null,
      null,
      null,
      null,
      null,
      1,
      emptySet(),
    )
  }

  override fun shouldLibraryHandlePatch(
    library: Library,
    target: MetadataPatchTarget,
  ): Boolean = target == MetadataPatchTarget.SERIES
}
