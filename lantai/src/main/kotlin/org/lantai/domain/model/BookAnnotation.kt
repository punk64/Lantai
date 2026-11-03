package org.lantai.domain.model

import com.github.f4b6a3.tsid.TsidCreator
import java.time.LocalDateTime

data class BookAnnotation(
  val bookId: String,
  val userId: String,
  val href: String,
  val fragment: String = "",
  val progression: Double? = null,
  val textBefore: String = "",
  val textHighlight: String = "",
  val textAfter: String = "",
  val color: String = "#FFEB3B",
  val marker: String = "highlight",
  val note: String = "",
  val selection: String? = null,
  val id: String = TsidCreator.getTsid256().toString(),
  override val createdDate: LocalDateTime = LocalDateTime.now(),
  override val lastModifiedDate: LocalDateTime = createdDate,
) : Auditable {
  enum class Marker {
    HIGHLIGHT,
    UNDERLINE,
    BOOKMARK,
    COMMENT,
  }
}
