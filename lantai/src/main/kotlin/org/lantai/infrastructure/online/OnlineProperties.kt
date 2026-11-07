package org.lantai.infrastructure.online

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "lantai.online")
class OnlineProperties {
  var enabled: Boolean = false
  var libraryName: String = "在线库"
  var rootDir: String = ""
  var suwayomiEndpoint: String = "http://127.0.0.1:25610"
  var sourceKind: String = "suwayomi"
  var suwayomiUsername: String = ""
  var suwayomiPassword: String = ""
  var suwayomiDownloadsDir: String = ""
  var downloadTimeoutSeconds: Int = 180
  var downloadPollMillis: Long = 2000
  var suwayomiHome: String = ""
  var suwayomiAutoStart: Boolean = true
  var suwayomiAutoInstall: Boolean = false
  var suwayomiLaunchTimeoutSeconds: Int = 120
  var suwayomiJavaPath: String = ""
  var suwayomiMinVersion: String = "2.0.0"
  var suwayomiMaxVersion: String = "3.0.0"
  var extensionIndexUrl: String = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.pb"
  var extensionIndexMirrorUrl: String = "https://cdn.jsdelivr.net/gh/keiyoushi/extensions@repo/index.pb"
  var extensionIndexCacheMinutes: Long = 720
  var extensionDownloadMirrors: List<String> = listOf("https://ghfast.top/", "https://gh-proxy.com/")
}
