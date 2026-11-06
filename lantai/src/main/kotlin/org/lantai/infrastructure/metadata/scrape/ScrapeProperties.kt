package org.lantai.infrastructure.metadata.scrape

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "lantai.scrape")
class ScrapeProperties {
  var sources: Map<String, SourceProperties> = emptyMap()
  var matchDailyQuota: Int = 50
  var matchConcurrency: Int = 1

  fun source(id: String): SourceProperties = sources[id] ?: SourceProperties()

  class SourceProperties {
    var enabled: Boolean = false
    var language: String = ""
    var displayName: String = ""
    var baseUrl: String = ""
    var minIntervalMs: Long = 1500
    var dailyQuota: Int = 0
    var cookie: String = ""
    var userAgent: String = ""
  }
}
