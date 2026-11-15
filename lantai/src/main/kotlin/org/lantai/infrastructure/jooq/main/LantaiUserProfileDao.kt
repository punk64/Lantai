package org.lantai.infrastructure.jooq.main

import org.jooq.DSLContext
import org.lantai.infrastructure.jooq.SplitDslDaoBase
import org.lantai.jooq.main.Tables
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.time.LocalDateTime

@Component
class LantaiUserProfileDao(
  dslRW: DSLContext,
  @Qualifier("dslContextRO") dslRO: DSLContext,
) : SplitDslDaoBase(dslRW, dslRO) {
  fun users(userIds: Collection<String>): Map<String, LantaiUserProfileRow> {
    if (userIds.isEmpty()) return emptyMap()
    val u = Tables.USER
    val p = Tables.USER_PROFILE
    return dslRO
      .select(u.ID, u.USERNAME, p.NICKNAME, p.AVATAR_FILE, p.UPDATED_DATE)
      .from(u)
      .leftJoin(p)
      .on(p.USER_ID.eq(u.ID))
      .where(u.ID.`in`(userIds))
      .fetch()
      .associate {
        it.value1() to
          LantaiUserProfileRow(
            username = it.value2(),
            nickname = it.value3() ?: "",
            avatarFile = it.value4() ?: "",
            updated = it.value5(),
          )
      }
  }

  fun user(userId: String): LantaiUserProfileRow? = users(listOf(userId))[userId]

  fun saveNickname(userId: String, nickname: String) {
    val p = Tables.USER_PROFILE
    val now = LocalDateTime.now()
    val updated =
      dslRW.update(p).set(p.NICKNAME, nickname).set(p.UPDATED_DATE, now).where(p.USER_ID.eq(userId)).execute()
    if (updated == 0) {
      dslRW
        .insertInto(p)
        .set(p.USER_ID, userId)
        .set(p.NICKNAME, nickname)
        .set(p.AVATAR_FILE, "")
        .set(p.UPDATED_DATE, now)
        .execute()
    }
  }

  fun saveAvatar(userId: String, file: String) {
    val p = Tables.USER_PROFILE
    val now = LocalDateTime.now()
    val updated =
      dslRW.update(p).set(p.AVATAR_FILE, file).set(p.UPDATED_DATE, now).where(p.USER_ID.eq(userId)).execute()
    if (updated == 0) {
      dslRW
        .insertInto(p)
        .set(p.USER_ID, userId)
        .set(p.NICKNAME, "")
        .set(p.AVATAR_FILE, file)
        .set(p.UPDATED_DATE, now)
        .execute()
    }
  }

  fun clearAvatar(userId: String) {
    val p = Tables.USER_PROFILE
    dslRW
      .update(p)
      .set(p.AVATAR_FILE, "")
      .set(p.UPDATED_DATE, LocalDateTime.now())
      .where(p.USER_ID.eq(userId))
      .execute()
  }
}

data class LantaiUserProfileRow(
  val username: String,
  val nickname: String,
  val avatarFile: String,
  val updated: LocalDateTime?,
)
