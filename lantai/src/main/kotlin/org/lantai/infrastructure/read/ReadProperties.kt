package org.lantai.infrastructure.read

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "lantai.read")
class ReadProperties {
  var defaultWindow: Int = 3
  var maxWindow: Int = 30
  var jpegQuality: Double = 0.82
  var inlineMaxImages: Int = 512
  var maxExportPages: Int = 300
}
