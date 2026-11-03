package org.lantai.domain.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.lantai.domain.model.ApiKey
import org.lantai.domain.model.DomainEvent
import org.lantai.domain.model.DuplicateNameException
import org.lantai.domain.model.LantaiUser
import org.lantai.domain.model.UserEmailAlreadyExistsException
import org.lantai.domain.persistence.AuthenticationActivityRepository
import org.lantai.domain.persistence.LantaiUserRepository
import org.lantai.domain.persistence.ReadProgressRepository
import org.lantai.domain.persistence.SyncPointRepository
import org.lantai.infrastructure.jooq.main.ClientSettingsDtoDao
import org.lantai.infrastructure.security.LantaiPrincipal
import org.lantai.infrastructure.security.TokenEncoder
import org.lantai.infrastructure.security.apikey.ApiKeyGenerator
import org.springframework.context.ApplicationEventPublisher
import org.springframework.security.core.session.SessionRegistry
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

private val logger = KotlinLogging.logger {}

@Service
class LantaiUserLifecycle(
  private val userRepository: LantaiUserRepository,
  private val readProgressRepository: ReadProgressRepository,
  private val authenticationActivityRepository: AuthenticationActivityRepository,
  private val syncPointRepository: SyncPointRepository,
  private val passwordEncoder: PasswordEncoder,
  private val tokenEncoder: TokenEncoder,
  private val sessionRegistry: SessionRegistry,
  private val transactionTemplate: TransactionTemplate,
  private val eventPublisher: ApplicationEventPublisher,
  private val apiKeyGenerator: ApiKeyGenerator,
  private val clientSettingsDtoDao: ClientSettingsDtoDao,
) {
  fun updatePassword(
    user: LantaiUser,
    newPassword: String,
    expireSessions: Boolean,
  ) {
    logger.info { "Changing password for user ${user.email}" }
    val updatedUser = user.copy(password = passwordEncoder.encode(newPassword))
    userRepository.update(updatedUser)

    if (expireSessions) expireSessions(updatedUser)

    eventPublisher.publishEvent(DomainEvent.UserUpdated(updatedUser, expireSessions))
  }

  fun updateUser(user: LantaiUser) {
    val existing = userRepository.findByIdOrNull(user.id)
    requireNotNull(existing) { "User doesn't exist, cannot update: $user" }

    val toUpdate = user.copy(password = existing.password)
    logger.info { "Update user: $toUpdate" }
    userRepository.update(toUpdate)

    val expireSessions =
      existing.roles != user.roles ||
        existing.restrictions != user.restrictions ||
        existing.sharedAllLibraries != user.sharedAllLibraries ||
        existing.sharedLibrariesIds != user.sharedLibrariesIds

    if (expireSessions) expireSessions(toUpdate)

    eventPublisher.publishEvent(DomainEvent.UserUpdated(toUpdate, expireSessions))
  }

  fun updateProfile(
    user: LantaiUser,
    username: String,
    email: String?,
  ): LantaiUser {
    val trimmed = username.trim()
    require(trimmed.isNotEmpty()) { "Username must not be blank" }
    if (!trimmed.equals(user.username, ignoreCase = true) && userRepository.existsByUsernameIgnoreCase(trimmed))
      throw UserEmailAlreadyExistsException("A user with the same username already exists: $trimmed")
    val normalizedEmail = email?.trim()?.takeIf { it.isNotEmpty() }
    normalizedEmail?.let {
      if (!it.equals(user.email ?: "", ignoreCase = true) && userRepository.existsByEmailIgnoreCase(it))
        throw UserEmailAlreadyExistsException("A user with the same email already exists: $it")
    }

    val updated = user.copy(username = trimmed, email = normalizedEmail)
    logger.info { "Update profile of user ${updated.id}" }
    userRepository.update(updated)
    eventPublisher.publishEvent(DomainEvent.UserUpdated(updated, false))
    return updated
  }

  fun countUsers() = userRepository.count()

  @Throws(UserEmailAlreadyExistsException::class)
  fun createUser(lantaiUser: LantaiUser): LantaiUser {
    require(lantaiUser.username.isNotBlank()) { "Username must not be blank" }
    if (userRepository.existsByUsernameIgnoreCase(lantaiUser.username))
      throw UserEmailAlreadyExistsException("A user with the same username already exists: ${lantaiUser.username}")
    lantaiUser.email?.takeIf { it.isNotBlank() }?.let { email ->
      if (userRepository.existsByEmailIgnoreCase(email))
        throw UserEmailAlreadyExistsException("A user with the same email already exists: $email")
    }

    userRepository.insert(lantaiUser.copy(password = passwordEncoder.encode(lantaiUser.password)))

    val createdUser = userRepository.findByIdOrNull(lantaiUser.id)!!
    logger.info { "User created: $createdUser" }
    return createdUser
  }

  fun deleteUser(user: LantaiUser) {
    logger.info { "Deleting user: $user" }

    transactionTemplate.executeWithoutResult {
      clientSettingsDtoDao.deleteByUserId(user.id)
      readProgressRepository.deleteByUserId(user.id)
      authenticationActivityRepository.deleteByUser(user)
      syncPointRepository.deleteByUserId(user.id)
      userRepository.delete(user.id)
    }

    expireSessions(user)

    eventPublisher.publishEvent(DomainEvent.UserUpdated(user, true))
  }

  fun expireSessions(user: LantaiUser) {
    logger.info { "Expiring all sessions for user: ${user.email}" }
    sessionRegistry
      .getAllSessions(LantaiPrincipal(user), false)
      .forEach {
        logger.info { "Expiring session: ${it.sessionId}" }
        it.expireNow()
      }
  }

  /**
   * Create and persist an API key for the user.
   * @return the ApiKey, or null if a unique API key could not be generated
   */
  fun createApiKey(
    user: LantaiUser,
    comment: String,
  ): ApiKey? {
    val commentTrimmed = comment.trim()
    if (userRepository.existsApiKeyByCommentAndUserId(commentTrimmed, user.id))
      throw DuplicateNameException("api key comment already exists for this user", "ERR_1034")
    for (attempt in 1..10) {
      try {
        val plainTextKey =
          ApiKey(
            userId = user.id,
            key = apiKeyGenerator.generate(),
            comment = commentTrimmed,
          )
        userRepository.insert(plainTextKey.copy(key = tokenEncoder.encode(plainTextKey.key)))
        return plainTextKey
      } catch (e: Exception) {
        logger.debug { "Failed to generate unique api key, attempt #$attempt" }
      }
    }
    return null
  }
}
