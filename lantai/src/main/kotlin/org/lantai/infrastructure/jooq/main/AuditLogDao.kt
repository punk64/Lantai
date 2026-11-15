package org.lantai.infrastructure.jooq.main

import org.lantai.domain.model.AuditLog
import org.lantai.domain.model.AuditLogFilter
import org.lantai.domain.persistence.AuditLogRepository
import org.lantai.infrastructure.jooq.SplitDslDaoBase
import org.lantai.jooq.main.Tables
import org.lantai.jooq.main.tables.records.AuditLogRecord
import org.lantai.language.toCurrentTimeZone
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.SortField
import org.jooq.impl.DSL
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.time.ZoneId

@Component
class AuditLogDao(
  dslRW: DSLContext,
  @Qualifier("dslContextRO") dslRO: DSLContext,
) : SplitDslDaoBase(dslRW, dslRO),
  AuditLogRepository {
  private val a = Tables.AUDIT_LOG

  private companion object {
    const val DEFAULT_PAGE_SIZE = 50
  }

  override fun insert(log: AuditLog) {
    dslRW
      .insertInto(a)
      .set(a.ID, log.id)
      .set(a.ACTOR_ID, log.actorId)
      .set(a.ACTOR_EMAIL, log.actorEmail)
      .set(a.ACTION, log.action)
      .set(a.TARGET_TYPE, log.targetType)
      .set(a.TARGET_ID, log.targetId)
      .set(a.METHOD, log.method)
      .set(a.PATH, log.path)
      .set(a.QUERY, log.query)
      .set(a.STATUS, log.status)
      .set(a.SUCCESS, log.success)
      .set(a.IP, log.ip)
      .set(a.USER_AGENT, log.userAgent)
      .set(a.CREATED_DATE, LocalDateTime.now(ZoneId.of("Z")))
      .execute()
  }

  override fun findByIdOrNull(id: String): AuditLog? =
    dslRO
      .selectFrom(a)
      .where(a.ID.eq(id))
      .fetchOne()
      ?.toDomain()

  override fun search(
    filter: AuditLogFilter,
    pageable: Pageable,
  ): Page<AuditLog> {
    val page = if (pageable.isPaged) pageable else PageRequest.of(0, DEFAULT_PAGE_SIZE)
    val condition = filter.toCondition()
    val total = dslRO.fetchCount(a, condition).toLong()
    val content =
      dslRO
        .selectFrom(a)
        .where(condition)
        .orderBy(page.toOrder())
        .limit(page.pageSize)
        .offset(page.offset)
        .fetch()
        .map { it.toDomain() }
    return PageImpl(content, page, total)
  }

  override fun findDistinctActions(): List<String> =
    dslRO
      .selectDistinct(a.ACTION)
      .from(a)
      .orderBy(a.ACTION)
      .fetch(a.ACTION)

  override fun deleteOlderThan(dateTime: LocalDateTime): Int =
    dslRW
      .deleteFrom(a)
      .where(a.CREATED_DATE.lt(dateTime))
      .execute()

  private fun AuditLogFilter.toCondition(): Condition {
    var condition = DSL.noCondition()
    actorId?.let { condition = condition.and(a.ACTOR_ID.eq(it)) }
    action?.let { condition = condition.and(a.ACTION.eq(it)) }
    targetType?.let { condition = condition.and(a.TARGET_TYPE.eq(it)) }
    targetId?.let { condition = condition.and(a.TARGET_ID.eq(it)) }
    success?.let { condition = condition.and(a.SUCCESS.eq(it)) }
    from?.let { condition = condition.and(a.CREATED_DATE.ge(it)) }
    to?.let { condition = condition.and(a.CREATED_DATE.le(it)) }
    q?.takeIf { it.isNotBlank() }?.let {
      val pattern = "%${it.trim().lowercase()}%"
      condition =
        condition.and(
          DSL
            .lower(a.ACTOR_EMAIL)
            .like(pattern)
            .or(DSL.lower(a.PATH).like(pattern))
            .or(DSL.lower(a.TARGET_ID).like(pattern))
            .or(DSL.lower(a.ACTION).like(pattern)),
        )
    }
    return condition
  }

  private fun Pageable.toOrder(): List<SortField<*>> {
    val sort = if (sort.isSorted) sort else Sort.by(Sort.Order.desc("createdDate"))
    return sort.toList().map {
      val field =
        when (it.property) {
          "actorEmail" -> a.ACTOR_EMAIL
          "action" -> a.ACTION
          "status" -> a.STATUS
          "method" -> a.METHOD
          else -> a.CREATED_DATE
        }
      if (it.isAscending) field.asc() else field.desc()
    }
  }

  private fun AuditLogRecord.toDomain() =
    AuditLog(
      action = action,
      method = method,
      path = path,
      status = status,
      actorId = actorId,
      actorEmail = actorEmail,
      targetType = targetType,
      targetId = targetId,
      query = query,
      ip = ip,
      userAgent = userAgent,
      id = id,
      createdDate = createdDate.toCurrentTimeZone(),
    )
}
