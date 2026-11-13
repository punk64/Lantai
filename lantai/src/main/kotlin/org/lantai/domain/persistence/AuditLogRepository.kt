package org.lantai.domain.persistence

import org.lantai.domain.model.AuditLog
import org.lantai.domain.model.AuditLogFilter
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import java.time.LocalDateTime

interface AuditLogRepository {
  fun insert(log: AuditLog)

  fun findByIdOrNull(id: String): AuditLog?

  fun search(
    filter: AuditLogFilter,
    pageable: Pageable,
  ): Page<AuditLog>

  fun findDistinctActions(): List<String>

  fun deleteOlderThan(dateTime: LocalDateTime): Int
}
