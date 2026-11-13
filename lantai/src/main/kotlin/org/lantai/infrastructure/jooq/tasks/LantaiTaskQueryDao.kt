package org.lantai.infrastructure.jooq.tasks

import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.DSL.count
import org.lantai.infrastructure.jooq.SplitDslDaoBase
import org.lantai.jooq.tasks.Tables
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.DependsOn
import org.springframework.stereotype.Component
import java.time.LocalDateTime

@Component
@DependsOn("flywayTasksMigrationInitializer")
class LantaiTaskQueryDao(
  @Qualifier("tasksDslContextRW") dslRW: DSLContext,
  @Qualifier("tasksDslContextRO") dslRO: DSLContext,
) : SplitDslDaoBase(dslRW, dslRO) {
  private val t = Tables.TASK

  fun list(
    page: Int,
    size: Int,
    type: String? = null,
    state: String? = null,
  ): List<LantaiTaskRow> =
    dslRO
      .select(t.ID, t.SIMPLE_TYPE, t.PRIORITY, t.GROUP_ID, t.OWNER, t.CREATED_DATE, t.PAYLOAD)
      .from(t)
      .where(filters(type, state))
      .orderBy(t.PRIORITY.desc(), t.CREATED_DATE)
      .limit(size)
      .offset(page * size)
      .fetch { r ->
        LantaiTaskRow(r[t.ID], r[t.SIMPLE_TYPE], r[t.PRIORITY], r[t.GROUP_ID], r[t.OWNER], r[t.CREATED_DATE], r[t.PAYLOAD])
      }

  fun countTasks(
    type: String? = null,
    state: String? = null,
  ): Int {
    val c = count()
    return dslRO.select(c).from(t).where(filters(type, state)).fetchOne()?.get(c) ?: 0
  }

  fun countRunning(): Int {
    val c = count()
    return dslRO.select(c).from(t).where(t.OWNER.isNotNull).fetchOne()?.get(c) ?: 0
  }

  fun countQueued(): Int {
    val c = count()
    return dslRO.select(c).from(t).where(t.OWNER.isNull).fetchOne()?.get(c) ?: 0
  }

  fun byType(): Map<String, Int> {
    val c = count()
    return dslRO
      .select(t.SIMPLE_TYPE, c)
      .from(t)
      .groupBy(t.SIMPLE_TYPE)
      .orderBy(c.desc())
      .fetch()
      .associate { r -> r[t.SIMPLE_TYPE] to r[c] }
  }

  fun deleteById(id: String): Int = dslRW.deleteFrom(t).where(t.ID.eq(id)).execute()

  fun updatePriority(
    id: String,
    priority: Int,
  ): Int =
    dslRW
      .update(t)
      .set(t.PRIORITY, priority)
      .where(t.ID.eq(id))
      .and(t.OWNER.isNull)
      .execute()

  fun deleteQueued(): Int = dslRW.deleteFrom(t).where(t.OWNER.isNull).execute()

  private fun filters(
    type: String?,
    state: String?,
  ): Condition {
    var c: Condition = DSL.noCondition()
    if (!type.isNullOrBlank()) c = c.and(t.SIMPLE_TYPE.eq(type))
    when (state) {
      "running" -> c = c.and(t.OWNER.isNotNull)
      "queued" -> c = c.and(t.OWNER.isNull)
    }
    return c
  }
}

data class LantaiTaskRow(
  val id: String,
  val type: String,
  val priority: Int,
  val groupId: String?,
  val owner: String?,
  val createdDate: LocalDateTime,
  val payload: String,
)
