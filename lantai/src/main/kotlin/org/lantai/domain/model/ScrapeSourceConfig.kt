package org.lantai.domain.model

import java.time.LocalDateTime

data class ScrapeSourceConfig(
  val id: String,
  val displayName: String,
  val kind: Kind,
  val baseUrl: String = "",
  val queryTemplate: String? = null,
  val resultPath: String = "",
  val fieldMap: Map<String, String> = emptyMap(),
  val headers: Map<String, String> = emptyMap(),
  val dailyQuota: Int = 0,
  val minIntervalMs: Long = 1500,
  val enabled: Boolean = true,
  val builtin: Boolean = false,
  val createdDate: LocalDateTime? = null,
  val lastModifiedDate: LocalDateTime? = null,
) {
  enum class Kind {
    BUILTIN,
    JSON,
    GRAPHQL,
    HTML,
  }
}
