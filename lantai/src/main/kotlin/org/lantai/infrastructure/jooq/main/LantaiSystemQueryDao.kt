package org.lantai.infrastructure.jooq.main

import org.jooq.DSLContext
import org.lantai.infrastructure.jooq.SplitDslDaoBase
import org.lantai.jooq.main.Tables
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

@Component
class LantaiSystemQueryDao(
  dslRW: DSLContext,
  @Qualifier("dslContextRO") dslRO: DSLContext,
) : SplitDslDaoBase(dslRW, dslRO) {
  fun countLibraries(): Int = dslRO.fetchCount(Tables.LIBRARY)

  fun countSeries(): Int = dslRO.fetchCount(Tables.SERIES)

  fun countBooks(): Int = dslRO.fetchCount(Tables.BOOK)

  fun countSessions(): Int = dslRO.fetchCount(Tables.READING_SESSION)

  fun countTrashEntries(): Int = dslRO.fetchCount(Tables.TRASH_ENTRY)
}
