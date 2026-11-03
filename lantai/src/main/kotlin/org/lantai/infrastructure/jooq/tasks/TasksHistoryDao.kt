package org.lantai.infrastructure.jooq.tasks

import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.SQLDataType
import org.jooq.impl.DSL
import org.lantai.infrastructure.jooq.SplitDslDaoBase
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.DependsOn
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

const val TASK_HISTORY_STATUS_DONE = "DONE"
const val TASK_HISTORY_STATUS_FAILED = "FAILED"
const val TASK_HISTORY_LIMIT = 2000

@Component
@DependsOn("flywayTasksMigrationInitializer")
class TasksHistoryDao(
  @Qualifier("tasksDslContextRW") dslRW: DSLContext,
  @Qualifier("tasksDslContextRO") dslRO: DSLContext,
) : SplitDslDaoBase(dslRW, dslRO) {
  private val historyTable = DSL.table("TASK_HISTORY")
  private val taskTable = DSL.table("TASK")
  private val colId = DSL.field("ID", SQLDataType.VARCHAR)
  private val colType = DSL.field("SIMPLE_TYPE", SQLDataType.VARCHAR)
  private val colPriority = DSL.field("PRIORITY", SQLDataType.INTEGER)
  private val colGroupId = DSL.field("GROUP_ID", SQLDataType.VARCHAR)
  private val colStatus = DSL.field("STATUS", SQLDataType.VARCHAR)
  private val colCreatedDate = DSL.field("CREATED_DATE", SQLDataType.LOCALDATETIME)
  private val colFinishedDate = DSL.field("FINISHED_DATE", SQLDataType.LOCALDATETIME)
  private val colDurationMs = DSL.field("DURATION_MS", SQLDataType.BIGINT)
  private val colError = DSL.field("ERROR", SQLDataType.VARCHAR)

  @Transactional
  fun save(row: TaskHistoryRow) {
    dslRW
      .insertInto(historyTable, colId, colType, colPriority, colGroupId, colStatus, colCreatedDate, colFinishedDate, colDurationMs, colError)
      .values(row.id, row.type, row.priority, row.groupId, row.status, row.createdDate, row.finishedDate, row.durationMs, row.error)
      .execute()
    trimTo(TASK_HISTORY_LIMIT)
  }

  fun list(
    page: Int,
    size: Int,
    type: String? = null,
    status: String? = null,
  ): List<TaskHistoryRow> =
    dslRO
      .select(colId, colType, colPriority, colGroupId, colStatus, colCreatedDate, colFinishedDate, colDurationMs, colError)
      .from(historyTable)
      .where(filters(type, status))
      .orderBy(colFinishedDate.desc())
      .limit(size)
      .offset(page * size)
      .fetch { r ->
        TaskHistoryRow(
          id = r[colId],
          type = r[colType],
          priority = r[colPriority],
          groupId = r[colGroupId],
          status = r[colStatus],
          createdDate = r[colCreatedDate],
          finishedDate = r[colFinishedDate],
          durationMs = r[colDurationMs],
          error = r[colError],
        )
      }

  fun count(
    type: String? = null,
    status: String? = null,
  ): Int {
    val c = DSL.count()
    return dslRO.select(c).from(historyTable).where(filters(type, status)).fetchOne()?.get(c) ?: 0
  }

  fun byType(): Map<String, Int> {
    val c = DSL.count()
    return dslRO
      .select(colType, c)
      .from(historyTable)
      .groupBy(colType)
      .orderBy(c.desc())
      .fetch()
      .associate { it.value1() to it.value2() }
  }

  @Transactional
  fun clear(): Int = dslRW.deleteFrom(historyTable).execute()

  @Transactional
  fun trimTo(limit: Int): Int {
    val excess = dslRW.fetchCount(historyTable) - limit
    if (excess <= 0) return 0
    val outdated =
      dslRW
        .select(colId)
        .from(historyTable)
        .orderBy(colFinishedDate.asc())
        .limit(excess)
        .fetch(colId)
    if (outdated.isEmpty()) return 0
    return dslRW.deleteFrom(historyTable).where(colId.`in`(outdated)).execute()
  }

  fun createdDate(taskId: String): LocalDateTime? =
    dslRO
      .select(colCreatedDate)
      .from(taskTable)
      .where(colId.eq(taskId))
      .fetchOne(colCreatedDate)

  private fun filters(
    type: String?,
    status: String?,
  ): Condition {
    var c: Condition = DSL.noCondition()
    if (!type.isNullOrBlank()) c = c.and(colType.eq(type))
    if (!status.isNullOrBlank()) c = c.and(colStatus.eq(status))
    return c
  }
}

data class TaskHistoryRow(
  val id: String,
  val type: String,
  val priority: Int,
  val groupId: String?,
  val status: String,
  val createdDate: LocalDateTime,
  val finishedDate: LocalDateTime,
  val durationMs: Long,
  val error: String?,
)
