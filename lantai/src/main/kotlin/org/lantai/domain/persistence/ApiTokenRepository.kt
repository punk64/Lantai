package org.lantai.domain.persistence

import org.lantai.domain.model.ApiToken
import java.time.LocalDateTime

interface ApiTokenRepository {
  fun insert(token: ApiToken)

  fun update(token: ApiToken)

  fun findByIdOrNull(id: String): ApiToken?

  fun findByTokenValueOrNull(tokenValue: String): ApiToken?

  fun findAll(): List<ApiToken>

  fun findAllByOwnerId(ownerId: String): List<ApiToken>

  fun existsByName(name: String, excludeId: String? = null): Boolean

  fun deleteById(id: String)

  fun recordUsage(id: String, date: LocalDateTime, ip: String?)
}
