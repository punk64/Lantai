package org.lantai.infrastructure.jooq.main

import org.lantai.domain.model.ApiToken
import org.lantai.domain.persistence.ApiTokenRepository
import org.lantai.infrastructure.jooq.SplitDslDaoBase
import org.lantai.infrastructure.security.apitoken.TokenCipher
import org.lantai.jooq.main.Tables
import org.lantai.jooq.main.tables.records.ApiTokenRecord
import org.lantai.language.toCurrentTimeZone
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneId

private const val HASH_PREFIX = "sha512:"

@Component
class ApiTokenDao(
  dslRW: DSLContext,
  @Qualifier("dslContextRO") dslRO: DSLContext,
  private val tokenCipher: TokenCipher,
) : SplitDslDaoBase(dslRW, dslRO),
  ApiTokenRepository {
  private val q = Tables.API_TOKEN

  override fun insert(token: ApiToken) {
    val now = LocalDateTime.now(ZoneId.of("Z"))
    dslRW
      .insertInto(q)
      .set(q.ID, token.id)
      .set(q.USER_ID, token.ownerId)
      .set(q.NAME, token.name)
      .set(q.TOKEN_VALUE, tokenCipher.encrypt(token.tokenValue))
      .set(q.TOKEN_HASH, hash(token.tokenValue))
      .set(q.SCOPES, token.scopes.joinToString(",") { it.label })
      .set(q.ENABLED, token.enabled)
      .set(q.EXPIRES_DATE, token.expiresDate?.atZone(ZoneId.systemDefault())?.withZoneSameInstant(ZoneId.of("Z"))?.toLocalDateTime())
      .set(q.USE_COUNT, 0)
      .set(q.CREATED_DATE, now)
      .set(q.LAST_MODIFIED_DATE, now)
      .execute()
  }

  override fun update(token: ApiToken) {
    dslRW
      .update(q)
      .set(q.NAME, token.name)
      .set(q.SCOPES, token.scopes.joinToString(",") { it.label })
      .set(q.ENABLED, token.enabled)
      .set(q.EXPIRES_DATE, token.expiresDate?.atZone(ZoneId.systemDefault())?.withZoneSameInstant(ZoneId.of("Z"))?.toLocalDateTime())
      .set(q.LAST_MODIFIED_DATE, LocalDateTime.now(ZoneId.of("Z")))
      .where(q.ID.eq(token.id))
      .execute()
  }

  override fun findByIdOrNull(id: String): ApiToken? =
    dslRO
      .selectFrom(q)
      .where(q.ID.eq(id))
      .fetchOne()
      ?.toDomain()

  override fun findByTokenValueOrNull(tokenValue: String): ApiToken? {
    val hashed = hash(tokenValue)
    dslRO
      .selectFrom(q)
      .where(q.TOKEN_HASH.eq(hashed))
      .fetchOne()
      ?.toDomain()
      ?.let { return it }
    dslRO
      .selectFrom(q)
      .where(q.TOKEN_VALUE.eq(hashed))
      .fetchOne()
      ?.toDomain()
      ?.let { stored ->
        runCatching {
          dslRW
            .update(q)
            .set(q.TOKEN_VALUE, tokenCipher.encrypt(tokenValue))
            .set(q.TOKEN_HASH, hashed)
            .where(q.ID.eq(stored.id))
            .execute()
        }
        return stored.copy(tokenValue = tokenValue)
      }
    val legacy =
      dslRO
        .selectFrom(q)
        .where(q.TOKEN_VALUE.eq(tokenValue))
        .fetchOne()
        ?.toDomain()
        ?: return null
    runCatching {
      dslRW
        .update(q)
        .set(q.TOKEN_VALUE, tokenCipher.encrypt(tokenValue))
        .set(q.TOKEN_HASH, hashed)
        .where(q.ID.eq(legacy.id))
        .execute()
    }
    return legacy.copy(tokenValue = tokenValue)
  }

  override fun findAll(): List<ApiToken> =
    dslRO
      .selectFrom(q)
      .orderBy(q.CREATED_DATE.desc())
      .fetch()
      .map { it.toDomain() }

  override fun findAllByOwnerId(ownerId: String): List<ApiToken> =
    dslRO
      .selectFrom(q)
      .where(q.USER_ID.eq(ownerId))
      .orderBy(q.CREATED_DATE.desc())
      .fetch()
      .map { it.toDomain() }

  override fun existsByName(name: String, excludeId: String?): Boolean =
    dslRO
      .fetchCount(
        q,
        q.NAME.equalIgnoreCase(name).and(excludeId?.let { q.ID.ne(it) } ?: DSL.noCondition()),
      ) > 0

  override fun deleteById(id: String) {
    dslRW
      .deleteFrom(q)
      .where(q.ID.eq(id))
      .execute()
  }

  override fun recordUsage(id: String, date: LocalDateTime, ip: String?) {
    dslRW
      .update(q)
      .set(q.LAST_USED_DATE, date.atZone(ZoneId.systemDefault()).withZoneSameInstant(ZoneId.of("Z")).toLocalDateTime())
      .set(q.LAST_USED_IP, ip)
      .set(q.USE_COUNT, q.USE_COUNT.plus(1))
      .where(q.ID.eq(id))
      .execute()
  }

  private fun hash(raw: String): String =
    if (raw.startsWith(HASH_PREFIX)) {
      raw
    } else {
      HASH_PREFIX +
        MessageDigest
          .getInstance("SHA-512")
          .digest(raw.toByteArray(Charsets.UTF_8))
          .joinToString("") { ((it.toInt() and 0xFF) + 0x100).toString(16).substring(1) }
    }

  private fun ApiTokenRecord.toDomain() =
    ApiToken(
      name = name,
      ownerId = userId,
      tokenValue = tokenCipher.decrypt(tokenValue) ?: tokenValue,
      scopes = ApiToken.Scope.fromStored(scopes),
      enabled = enabled,
      expiresDate = expiresDate?.toCurrentTimeZone(),
      lastUsedDate = lastUsedDate?.toCurrentTimeZone(),
      lastUsedIp = lastUsedIp,
      useCount = useCount,
      id = id,
      createdDate = createdDate.toCurrentTimeZone(),
      lastModifiedDate = lastModifiedDate.toCurrentTimeZone(),
    )
}
