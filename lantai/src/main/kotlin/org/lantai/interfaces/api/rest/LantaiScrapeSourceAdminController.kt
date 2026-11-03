package org.lantai.interfaces.api.rest

import org.lantai.domain.model.ScrapeSourceConfig
import org.lantai.infrastructure.metadata.scrape.ScrapeSourceRegistry
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("api/lantai/v1/scrape", produces = [MediaType.APPLICATION_JSON_VALUE])
@PreAuthorize("hasRole('ADMIN')")
class LantaiScrapeSourceAdminController(
  private val registry: ScrapeSourceRegistry,
) {
  @PostMapping("sources")
  fun create(
    @RequestBody body: ScrapeSourceUpsertRequest,
  ): ScrapeSourceInfoDto {
    if (body.id.isBlank()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少数据源 ID")
    if (!body.id.matches(ID_PATTERN)) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "数据源 ID 只能用小写字母、数字、短横线")
    val created =
      runCatching { registry.create(body.toConfig()) }
        .getOrElse { throw ResponseStatusException(HttpStatus.CONFLICT, it.message) }
    return created.toDto()
  }

  @PatchMapping("sources/{id}")
  fun update(
    @PathVariable id: String,
    @RequestBody body: ScrapeSourceUpsertRequest,
  ): ScrapeSourceInfoDto {
    val updated =
      runCatching { registry.update(id, body.toConfig()) }
        .getOrElse { throw ResponseStatusException(HttpStatus.NOT_FOUND, it.message) }
    return updated.toDto()
  }

  @GetMapping("sources/{id}")
  fun detail(
    @PathVariable id: String,
  ): ScrapeSourceDetailDto {
    val config = registry.findConfig(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "数据源 $id 不存在")
    return ScrapeSourceDetailDto(
      id = config.id,
      displayName = config.displayName,
      kind = config.kind.name,
      baseUrl = config.baseUrl,
      queryTemplate = config.queryTemplate.orEmpty(),
      resultPath = config.resultPath,
      fieldMap = config.fieldMap,
      headers = config.headers,
      dailyQuota = config.dailyQuota,
      minIntervalMs = config.minIntervalMs,
      enabled = config.enabled,
      builtin = config.builtin,
    )
  }

  @PostMapping("sources/{id}/enabled")
  fun toggle(
    @PathVariable id: String,
    @RequestParam(name = "value") value: Boolean,
  ) {
    runCatching { registry.setEnabled(id, value) }
      .getOrElse { throw ResponseStatusException(HttpStatus.NOT_FOUND, it.message) }
  }

  @DeleteMapping("sources/{id}")
  fun delete(
    @PathVariable id: String,
  ) {
    runCatching { registry.delete(id) }
      .getOrElse { throw ResponseStatusException(HttpStatus.BAD_REQUEST, it.message) }
  }

  @PostMapping("sources/{id}/test")
  fun test(
    @PathVariable id: String,
    @RequestParam(name = "q") query: String,
  ): List<Map<String, String>> =
    runCatching { registry.testSearch(id, query) }
      .getOrElse {
        throw ResponseStatusException(
          HttpStatus.BAD_GATEWAY,
          it.message ?: "测试请求失败",
        )
      }.map { mapOf("title" to it) }

  private fun ScrapeSourceConfig.toDto(): ScrapeSourceInfoDto =
    ScrapeSourceInfoDto(
      id = id,
      displayName = displayName,
      enabled = enabled,
      usageToday = 0,
      dailyQuota = dailyQuota,
      kind = kind.name,
      builtin = builtin,
      baseUrl = baseUrl,
    )

  companion object {
    private val ID_PATTERN = Regex("^[a-z0-9][a-z0-9-]{1,63}$")
  }
}

data class ScrapeSourceDetailDto(
  val id: String,
  val displayName: String,
  val kind: String,
  val baseUrl: String,
  val queryTemplate: String,
  val resultPath: String,
  val fieldMap: Map<String, String>,
  val headers: Map<String, String>,
  val dailyQuota: Int,
  val minIntervalMs: Long,
  val enabled: Boolean,
  val builtin: Boolean,
)

data class ScrapeSourceUpsertRequest(
  val id: String = "",
  val displayName: String = "",
  val kind: String = "JSON",
  val baseUrl: String = "",
  val queryTemplate: String = "",
  val resultPath: String = "",
  val fieldMap: Map<String, String> = emptyMap(),
  val headers: Map<String, String> = emptyMap(),
  val dailyQuota: Int = 0,
  val minIntervalMs: Long = 1500,
  val enabled: Boolean = true,
) {
  fun toConfig(): ScrapeSourceConfig =
    ScrapeSourceConfig(
      id = id.trim(),
      displayName = displayName.trim().ifBlank { id.trim() },
      kind = runCatching { ScrapeSourceConfig.Kind.valueOf(kind.uppercase()) }
        .getOrDefault(ScrapeSourceConfig.Kind.JSON),
      baseUrl = baseUrl.trim(),
      queryTemplate = queryTemplate.ifBlank { null },
      resultPath = resultPath.trim(),
      fieldMap = fieldMap.filter { it.value.isNotBlank() },
      headers = headers.filter { it.value.isNotBlank() },
      dailyQuota = dailyQuota.coerceAtLeast(0),
      minIntervalMs = minIntervalMs.coerceIn(200, 600000),
      enabled = enabled,
    )
}
