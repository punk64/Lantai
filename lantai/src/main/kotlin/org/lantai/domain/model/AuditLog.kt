package org.lantai.domain.model

import com.github.f4b6a3.tsid.TsidCreator
import java.time.LocalDateTime

data class AuditLog(
  val action: String,
  val method: String,
  val path: String,
  val status: Int,
  val actorId: String? = null,
  val actorEmail: String? = null,
  val targetType: String? = null,
  val targetId: String? = null,
  val query: String? = null,
  val ip: String? = null,
  val userAgent: String? = null,
  val id: String = TsidCreator.getTsid256().toString(),
  val createdDate: LocalDateTime = LocalDateTime.now(),
) {
  val success: Boolean
    get() = status in 200..399
}

data class AuditLogFilter(
  val actorId: String? = null,
  val action: String? = null,
  val targetType: String? = null,
  val targetId: String? = null,
  val success: Boolean? = null,
  val from: LocalDateTime? = null,
  val to: LocalDateTime? = null,
  val q: String? = null,
)
