package org.lantai.domain.model

import com.github.f4b6a3.tsid.TsidCreator
import java.time.LocalDateTime

data class ScrapeInboxEntry(
  val queueId: String,
  val seriesId: String,
  val payload: String,
  val status: Status = Status.RECEIVED,
  val tokenId: String? = null,
  val source: String? = null,
  val message: String? = null,
  val id: String = TsidCreator.getTsid256().toString(),
  override val createdDate: LocalDateTime = LocalDateTime.now(),
  override val lastModifiedDate: LocalDateTime = createdDate,
) : Auditable {
  enum class Status {
    RECEIVED,
    PARSED,
    REJECTED,
  }
}
