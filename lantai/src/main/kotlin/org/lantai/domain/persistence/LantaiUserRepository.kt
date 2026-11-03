package org.lantai.domain.persistence

import org.lantai.domain.model.ApiKey
import org.lantai.domain.model.LantaiUser
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable

interface LantaiUserRepository {
  fun count(): Long

  fun findByIdOrNull(id: String): LantaiUser?

  fun findByEmailIgnoreCaseOrNull(email: String): LantaiUser?

  fun findByUsernameIgnoreCaseOrNull(username: String): LantaiUser?

  fun findByLoginOrNull(login: String): LantaiUser?

  fun findByApiKeyOrNull(apiKey: String): Pair<LantaiUser, ApiKey>?

  fun findAll(): Collection<LantaiUser>

  fun search(
    q: String?,
    role: String?,
    enabled: Boolean?,
    pageable: Pageable,
  ): Page<LantaiUser>

  fun findApiKeyByUserId(userId: String): Collection<ApiKey>

  fun existsByEmailIgnoreCase(email: String): Boolean

  fun existsByUsernameIgnoreCase(username: String): Boolean

  fun existsApiKeyByIdAndUserId(
    apiKeyId: String,
    userId: String,
  ): Boolean

  fun existsApiKeyByCommentAndUserId(
    comment: String,
    userId: String,
  ): Boolean

  fun insert(user: LantaiUser)

  fun insert(apiKey: ApiKey)

  fun update(user: LantaiUser)

  fun delete(userId: String)

  fun deleteAll()

  fun deleteApiKeyByIdAndUserId(
    apiKeyId: String,
    userId: String,
  )

  fun deleteApiKeyByUserId(userId: String)

  fun findAnnouncementIdsReadByUserId(userId: String): Set<String>

  fun saveAnnouncementIdsRead(
    user: LantaiUser,
    announcementIds: Set<String>,
  )
}
