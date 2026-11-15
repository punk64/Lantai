package org.lantai.infrastructure.metadata

import org.lantai.domain.model.BookWithMedia
import org.lantai.domain.model.SeriesMetadataPatch

interface SeriesMetadataFromBookProvider : MetadataProvider {
  val supportsAppendVolume: Boolean

  fun getSeriesMetadataFromBook(
    book: BookWithMedia,
    appendVolumeToTitle: Boolean,
  ): SeriesMetadataPatch?
}
