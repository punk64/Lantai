package org.lantai.infrastructure.app

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "lantai.app")
class AppProperties {
  var homeLimit: Int = 16
  var maxHomeLimit: Int = 50
}
