package org.lantai.infrastructure.configuration

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "lantai.reading")
class ReadingStatsProperties {
  var rollupEnabled: Boolean = true
  var retentionDays: Int = 0
}
