package org.lantai.domain.model

import com.github.f4b6a3.tsid.TsidCreator
import java.time.LocalDateTime

data class OnlineCacheJob(
  val seriesId: String,
  val status: Status,
  val total: Int = 0,
  val done: Int = 0,
  val failed: Int = 0,
  val id: String = TsidCreator.getTsid256().toString(),
  override val createdDate: LocalDateTime = LocalDateTime.now(),
  override val lastModifiedDate: LocalDateTime = LocalDateTime.now(),
) : Auditable {
  enum class Status {
    PENDING,
    RUNNING,
    PAUSED,
    DONE,
    CANCELLED,
  }
}

data class OnlineCacheItem(
  val jobId: String,
  val bookId: String,
  val chapIndex: Int,
  val title: String,
  val status: Status,
  val error: String = "",
  val pageCount: Int = 0,
  val sizeBytes: Long = 0,
  val id: String = TsidCreator.getTsid256().toString(),
  override val createdDate: LocalDateTime = LocalDateTime.now(),
  override val lastModifiedDate: LocalDateTime = LocalDateTime.now(),
) : Auditable {
  enum class Status {
    PENDING,
    RUNNING,
    DONE,
    FAILED,
    SKIPPED,
  }
}
