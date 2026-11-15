package org.lantai.infrastructure.jooq.main

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.lantai.domain.model.ScrapeSourceConfig
import org.lantai.domain.persistence.ScrapeSourceRepository
import org.lantai.infrastructure.jooq.SplitDslDaoBase
import org.lantai.jooq.main.Tables
import org.lantai.jooq.main.tables.records.ScrapeSourceRecord
import org.lantai.language.toCurrentTimeZone
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.time.ZoneId

@Component
class ScrapeSourceDao(
  dslRW: DSLContext,
  @Qualifier("dslContextRO") dslRO: DSLContext,
  private val objectMapper: ObjectMapper,
) : SplitDslDaoBase(dslRW, dslRO),
  ScrapeSourceRepository {
  private val m = Tables.SCRAPE_SOURCE

  override fun findAll(): List<ScrapeSourceConfig> =
    dslRO
      .selectFrom(m)
      .orderBy(m.BUILTIN.desc(), m.DISPLAY_NAME.asc())
      .fetch()
      .map { it.toDomain() }

  override fun findByIdOrNull(id: String): ScrapeSourceConfig? =
    dslRO
      .selectFrom(m)
      .where(m.ID.eq(id))
      .fetchOne()
      ?.toDomain()

  override fun insert(config: ScrapeSourceConfig) {
    val now = LocalDateTime.now(ZoneId.of("Z"))
    dslRW
      .insertInto(m)
      .set(m.ID, config.id)
      .set(m.DISPLAY_NAME, config.displayName)
      .set(m.KIND, config.kind.name)
      .set(m.BASE_URL, config.baseUrl)
      .set(m.QUERY_TEMPLATE, config.queryTemplate)
      .set(m.RESULT_PATH, config.resultPath)
      .set(m.FIELD_MAP, objectMapper.writeValueAsString(config.fieldMap))
      .set(m.HEADERS, objectMapper.writeValueAsString(config.headers))
      .set(m.DAILY_QUOTA, config.dailyQuota)
      .set(m.MIN_INTERVAL_MS, config.minIntervalMs.toInt())
      .set(m.ENABLED, config.enabled)
      .set(m.BUILTIN, config.builtin)
      .set(m.CREATED_DATE, now)
      .set(m.LAST_MODIFIED_DATE, now)
      .execute()
  }

  override fun update(config: ScrapeSourceConfig) {
    dslRW
      .update(m)
      .set(m.DISPLAY_NAME, config.displayName)
      .set(m.KIND, config.kind.name)
      .set(m.BASE_URL, config.baseUrl)
      .set(m.QUERY_TEMPLATE, config.queryTemplate)
      .set(m.RESULT_PATH, config.resultPath)
      .set(m.FIELD_MAP, objectMapper.writeValueAsString(config.fieldMap))
      .set(m.HEADERS, objectMapper.writeValueAsString(config.headers))
      .set(m.DAILY_QUOTA, config.dailyQuota)
      .set(m.MIN_INTERVAL_MS, config.minIntervalMs.toInt())
      .set(m.ENABLED, config.enabled)
      .set(m.LAST_MODIFIED_DATE, LocalDateTime.now(ZoneId.of("Z")))
      .where(m.ID.eq(config.id))
      .execute()
  }

  override fun delete(id: String) {
    dslRW
      .deleteFrom(m)
      .where(m.ID.eq(id))
      .and(m.BUILTIN.isFalse)
      .execute()
  }

  override fun setEnabled(
    id: String,
    enabled: Boolean,
  ) {
    dslRW
      .update(m)
      .set(m.ENABLED, enabled)
      .set(m.LAST_MODIFIED_DATE, LocalDateTime.now(ZoneId.of("Z")))
      .where(m.ID.eq(id))
      .execute()
  }

  private fun ScrapeSourceRecord.toDomain() =
    ScrapeSourceConfig(
      id = id,
      displayName = displayName,
      kind = ScrapeSourceConfig.Kind.valueOf(kind),
      baseUrl = baseUrl ?: "",
      queryTemplate = queryTemplate,
      resultPath = resultPath ?: "",
      fieldMap = readMap(fieldMap),
      headers = readMap(headers),
      dailyQuota = dailyQuota ?: 0,
      minIntervalMs = (minIntervalMs ?: 1500).toLong(),
      enabled = enabled ?: false,
      builtin = builtin ?: false,
      createdDate = createdDate.toCurrentTimeZone(),
      lastModifiedDate = lastModifiedDate.toCurrentTimeZone(),
    )

  private fun readMap(raw: String?): Map<String, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    return runCatching {
      objectMapper.readValue<Map<String, String>>(raw, object : TypeReference<Map<String, String>>() {})
    }.getOrDefault(emptyMap())
  }
}
