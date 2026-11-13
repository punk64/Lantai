package org.lantai.infrastructure.metadata.scrape

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.lantai.domain.model.ScrapeSourceConfig
import org.lantai.domain.persistence.ScrapeSourceRepository
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import jakarta.annotation.PostConstruct

private val logger = KotlinLogging.logger {}

@Service
class ScrapeSourceRegistry(
  private val repository: ScrapeSourceRepository,
  private val builtins: List<ScrapeSource>,
  private val properties: ScrapeProperties,
  private val mapper: ObjectMapper,
  private val rateLimiter: ScrapeRateLimiter,
  private val webClientBuilder: WebClient.Builder,
) {
  @PostConstruct
  fun seedBuiltins() {
    builtins.forEach { bean ->
      if (repository.findByIdOrNull(bean.id) != null) return@forEach
      val defaults = properties.source(bean.id)
      runCatching {
        repository.insert(
          ScrapeSourceConfig(
            id = bean.id,
            displayName = bean.displayName,
            kind = ScrapeSourceConfig.Kind.BUILTIN,
            baseUrl = defaults.baseUrl,
            dailyQuota = defaults.dailyQuota,
            minIntervalMs = defaults.minIntervalMs,
            enabled = bean.isEnabled(),
            builtin = true,
          ),
        )
        logger.info { "Seeded scrape source ${bean.id}" }
      }.onFailure { logger.warn(it) { "Failed to seed scrape source ${bean.id}" } }
    }
    properties.sources.forEach { (id, defaults) ->
      if (builtins.any { it.id == id }) return@forEach
      if (repository.findByIdOrNull(id) != null) return@forEach
      runCatching {
        repository.insert(
          ScrapeSourceConfig(
            id = id,
            displayName = defaults.displayName.ifBlank { id },
            kind = ScrapeSourceConfig.Kind.BUILTIN,
            baseUrl = defaults.baseUrl,
            dailyQuota = defaults.dailyQuota,
            minIntervalMs = defaults.minIntervalMs,
            enabled = false,
            builtin = true,
          ),
        )
        logger.info { "Seeded reserved scrape source $id" }
      }.onFailure { logger.warn(it) { "Failed to seed reserved source $id" } }
    }
  }

  fun list(): List<ScrapeSourceConfig> = repository.findAll()

  fun findConfig(id: String): ScrapeSourceConfig? = repository.findByIdOrNull(id)

  fun enabled(): List<ScrapeSource> =
    repository.findAll().filter { it.enabled }.mapNotNull { toSource(it) }

  fun find(id: String): ScrapeSource? =
    repository.findByIdOrNull(id)?.takeIf { it.enabled }?.let { toSource(it) }

  fun toSource(config: ScrapeSourceConfig): ScrapeSource? {
    rateLimiter.overrideQuota(config.id, config.dailyQuota)
    rateLimiter.overrideInterval(config.id, config.minIntervalMs)
    if (config.builtin) return builtins.firstOrNull { it.id == config.id }
    return DeclarativeScrapeSource(config, mapper, rateLimiter, webClientBuilder)
  }

  fun create(config: ScrapeSourceConfig): ScrapeSourceConfig {
    if (repository.findByIdOrNull(config.id) != null) throw IllegalArgumentException("数据源 ${config.id} 已存在")
    repository.insert(config.copy(builtin = false))
    return repository.findByIdOrNull(config.id)!!
  }

  fun update(
    id: String,
    config: ScrapeSourceConfig,
  ): ScrapeSourceConfig {
    val existing = repository.findByIdOrNull(id) ?: throw NoSuchElementException("数据源 $id 不存在")
    val merged =
      config.copy(
        id = id,
        builtin = existing.builtin,
        kind = if (existing.builtin) ScrapeSourceConfig.Kind.BUILTIN else config.kind,
      )
    if (existing.builtin) {
      repository.setEnabled(id, merged.enabled)
    } else {
      repository.update(merged)
    }
    return repository.findByIdOrNull(id)!!
  }

  fun setEnabled(
    id: String,
    enabled: Boolean,
  ) {
    if (repository.findByIdOrNull(id) == null) throw NoSuchElementException("数据源 $id 不存在")
    repository.setEnabled(id, enabled)
  }

  fun delete(id: String) {
    val existing = repository.findByIdOrNull(id) ?: throw NoSuchElementException("数据源 $id 不存在")
    if (existing.builtin) throw IllegalArgumentException("内置数据源不可删除，只能停用")
    repository.delete(id)
  }

  fun testSearch(
    id: String,
    query: String,
  ): List<String> {
    val source = find(id) ?: throw NoSuchElementException("数据源 $id 不存在或未启用")
    return source.search(query, 5).map { it.title }
  }
}
