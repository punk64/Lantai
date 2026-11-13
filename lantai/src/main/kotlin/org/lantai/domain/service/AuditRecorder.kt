package org.lantai.domain.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.lantai.domain.model.AuditLog
import org.lantai.domain.persistence.AuditLogRepository
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.time.ZoneId

private val logger = KotlinLogging.logger {}

@Service
class AuditRecorder(
  private val auditLogRepository: AuditLogRepository,
) {
  fun record(log: AuditLog) {
    try {
      auditLogRepository.insert(log)
    } catch (e: Exception) {
      logger.warn(e) { "Failed to persist audit entry" }
    }
  }

  fun purgeOlderThan(days: Long): Int =
    auditLogRepository.deleteOlderThan(LocalDateTime.now(ZoneId.of("Z")).minusDays(days))
}
