package org.lantai.infrastructure.jooq

import org.lantai.infrastructure.online.OnlineAvailability
import org.lantai.jooq.main.Tables
import org.jooq.Condition
import org.jooq.Field
import org.jooq.impl.DSL

internal fun notHiddenOnlineSeries(field: Field<String>): Condition {
  val hidden = OnlineAvailability.hiddenKinds()
  if (hidden.isEmpty()) return DSL.noCondition()
  val hiddenSeries =
    DSL
      .select(Tables.ONLINE_MANGA.SERIES_ID)
      .from(Tables.ONLINE_MANGA)
      .where(Tables.ONLINE_MANGA.SOURCE_KIND.`in`(hidden))
  return field.notIn(hiddenSeries)
}
