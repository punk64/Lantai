package org.lantai.infrastructure.jooq.main

import org.lantai.domain.model.LantaiUser
import org.lantai.infrastructure.jooq.SplitDslDaoBase
import org.lantai.jooq.main.Tables
import org.jooq.Condition
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.time.LocalDateTime

@Component
class LantaiAuthActivityDao(
  dslRW: DSLContext,
  @Qualifier("dslContextRO") dslRO: DSLContext,
) : SplitDslDaoBase(dslRW, dslRO) {
  private val aa = Tables.AUTHENTICATION_ACTIVITY

  private fun own(user: LantaiUser): Condition = aa.USER_ID.eq(user.id).or(aa.EMAIL.eq(user.email ?: ""))

  private fun moment(
    ip: String?,
    dateTime: LocalDateTime,
  ): Condition {
    val from = dateTime.withNano(0)
    val window = aa.DATE_TIME.ge(from).and(aa.DATE_TIME.lt(from.plusSeconds(1)))
    return if (ip.isNullOrBlank()) window else window.and(aa.IP.eq(ip))
  }

  fun deleteAll(user: LantaiUser?): Int =
    dslRW
      .deleteFrom(aa)
      .apply { if (user != null) where(own(user)) }
      .execute()

  fun deleteEntries(
    user: LantaiUser?,
    items: List<Pair<String?, LocalDateTime>>,
  ): Int {
    if (items.isEmpty()) return 0
    val cond = items.map { moment(it.first, it.second) }.reduce { a, b -> a.or(b) }
    return dslRW
      .deleteFrom(aa)
      .where(if (user != null) cond.and(own(user)) else cond)
      .execute()
  }
}
