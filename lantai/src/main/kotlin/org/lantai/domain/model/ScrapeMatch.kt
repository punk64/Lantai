package org.lantai.domain.model

import com.github.f4b6a3.tsid.TsidCreator
import java.time.LocalDateTime

data class ScrapeMatch(
  val seriesId: String,
  val seriesTitle: String,
  val source: String,
  val sourceId: String,
  val score: Double,
  val status: Status = Status.PENDING,
  val candidate: ScrapeCandidate,
  val snapshot: String? = null,
  val appliedFields: Set<String> = emptySet(),
  val id: String = TsidCreator.getTsid256().toString(),
  override val createdDate: LocalDateTime = LocalDateTime.now(),
  override val lastModifiedDate: LocalDateTime = createdDate,
) : Auditable {
  enum class Status {
    PENDING,
    APPLIED,
    SKIPPED,
  }
}
