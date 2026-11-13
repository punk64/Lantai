package org.lantai.infrastructure.online

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "lantai.online")
class OnlineProperties {
  var libraryName: String = "在线库"
  var rootDir: String = ""
  var sourceKind: String = "plugin"
  var downloadTimeoutSeconds: Int = 180
}
