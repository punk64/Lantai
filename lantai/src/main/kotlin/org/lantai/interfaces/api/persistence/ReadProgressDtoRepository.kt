package org.lantai.interfaces.api.persistence

import org.lantai.interfaces.api.rest.dto.TachiyomiReadProgressDto
import org.lantai.interfaces.api.rest.dto.TachiyomiReadProgressV2Dto

interface ReadProgressDtoRepository {
  fun findProgressV2BySeries(
    seriesId: String,
    userId: String,
  ): TachiyomiReadProgressV2Dto

  fun findProgressByReadList(
    readListId: String,
    userId: String,
  ): TachiyomiReadProgressDto
}
