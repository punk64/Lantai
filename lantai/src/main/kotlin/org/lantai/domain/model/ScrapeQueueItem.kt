package org.lantai.domain.model

import com.github.f4b6a3.tsid.TsidCreator
import java.time.LocalDateTime

data class ScrapeQueueItem(
  val seriesId: String,
  val seriesTitle: String,
  val status: Status = Status.QUEUED,
  val origin: Origin = Origin.SERIES,
  val libraryId: String? = null,
  val bookIds: List<String> = emptyList(),
  val matchId: String? = null,
  val score: Double? = null,
  val source: String? = null,
  val message: String? = null,
  val attempt: Int = 0,
  val progress: Int = 0,
  val diff: String? = null,
  val result: String? = null,
  val lastError: String? = null,
  val finishedDate: LocalDateTime? = null,
  val id: String = TsidCreator.getTsid256().toString(),
  override val createdDate: LocalDateTime = LocalDateTime.now(),
  override val lastModifiedDate: LocalDateTime = createdDate,
) : Auditable {
  enum class Status {
    QUEUED,
    RUNNING,
    REVIEW,
    FAILED,
  }

  enum class Origin {
    SERIES,
    MULTI,
    LIBRARY,
  }
}
