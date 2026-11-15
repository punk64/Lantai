package org.lantai.domain.model

import com.github.f4b6a3.tsid.TsidCreator
import java.time.LocalDateTime

data class TrashEntry(
  val itemType: ItemType,
  val libraryId: String,
  val originalPath: String,
  val trashedPath: String,
  val sizeBytes: Long,
  val seriesId: String? = null,
  val bookId: String? = null,
  val deletedDate: LocalDateTime = LocalDateTime.now(),
  val id: String = TsidCreator.getTsid256().toString(),
) {
  enum class ItemType {
    SERIES,
    BOOK,
  }
}
