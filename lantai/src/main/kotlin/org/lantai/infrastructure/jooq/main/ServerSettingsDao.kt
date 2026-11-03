package org.lantai.infrastructure.jooq.main

import org.lantai.infrastructure.jooq.SplitDslDaoBase
import org.lantai.jooq.main.Tables
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

@Component
class ServerSettingsDao(
  dslRW: DSLContext,
  @Qualifier("dslContextRO") dslRO: DSLContext,
) : SplitDslDaoBase(dslRW, dslRO) {
  private val s = Tables.SERVER_SETTINGS

  fun <T> getSettingByKey(
    key: String,
    clazz: Class<T>,
  ): T? =
    dslRO
      .select(s.VALUE)
      .from(s)
      .where(s.KEY.eq(key))
      .fetchOneInto(clazz)

  fun saveSetting(
    key: String,
    value: String,
  ) {
    dslRW
      .insertInto(s)
      .values(key, value)
      .onDuplicateKeyUpdate()
      .set(s.VALUE, value)
      .execute()
  }

  fun saveSetting(
    key: String,
    value: Boolean,
  ) {
    saveSetting(key, value.toString())
  }

  fun saveSetting(
    key: String,
    value: Int,
  ) {
    saveSetting(key, value.toString())
  }

  fun saveSetting(
    key: String,
    value: Long,
  ) {
    saveSetting(key, value.toString())
  }

  fun deleteSetting(key: String) {
    dslRW.deleteFrom(s).where(s.KEY.eq(key)).execute()
  }

  fun deleteAll() {
    dslRW.deleteFrom(s).execute()
  }
}
