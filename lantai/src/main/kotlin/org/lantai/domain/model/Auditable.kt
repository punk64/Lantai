package org.lantai.domain.model

import java.time.LocalDateTime

interface Auditable {
  val createdDate: LocalDateTime
  val lastModifiedDate: LocalDateTime
}
