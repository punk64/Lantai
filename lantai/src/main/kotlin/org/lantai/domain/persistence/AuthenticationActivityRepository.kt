package org.lantai.domain.persistence

import org.lantai.domain.model.AuthenticationActivity
import org.lantai.domain.model.LantaiUser
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import java.time.LocalDateTime

interface AuthenticationActivityRepository {
  fun findAll(pageable: Pageable): Page<AuthenticationActivity>

  fun findAllByUser(
    user: LantaiUser,
    pageable: Pageable,
  ): Page<AuthenticationActivity>

  fun findMostRecentByUser(
    user: LantaiUser,
    apiKeyId: String?,
  ): AuthenticationActivity?

  fun insert(activity: AuthenticationActivity)

  fun deleteByUser(user: LantaiUser)

  fun deleteOlderThan(dateTime: LocalDateTime)
}
