package org.lantai.infrastructure.calibre

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "lantai.calibre")
class CalibreProperties {
  var enabled: Boolean = true
  var portableUrl: String = "https://calibre-ebook.com/dist/portable"
  var linuxBaseUrl: String = "https://download.calibre-ebook.com"
  var releasesApi: String = "https://api.github.com/repos/kovidgoyal/calibre/releases/latest"
  var mirror: String = ""
  var installDir: String = ""
  var executablePath: String = ""
  var convertTimeoutSeconds: Int = 300
  var installTimeoutSeconds: Int = 900
  var batchLimit: Int = 100
}

class CalibreException(
  message: String,
  cause: Throwable? = null,
) : RuntimeException(message, cause)
