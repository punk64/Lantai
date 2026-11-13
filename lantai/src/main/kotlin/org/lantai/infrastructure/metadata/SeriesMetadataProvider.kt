package org.lantai.infrastructure.metadata

import org.lantai.domain.model.Series
import org.lantai.domain.model.SeriesMetadataPatch

interface SeriesMetadataProvider : MetadataProvider {
  fun getSeriesMetadata(series: Series): SeriesMetadataPatch?
}
