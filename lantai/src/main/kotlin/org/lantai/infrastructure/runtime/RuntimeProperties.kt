package org.lantai.infrastructure.runtime

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "lantai.runtime")
class RuntimeProperties {
  var autoInstall: String = ""
  var autoEnable: Boolean = false
  var sourceMode: String = SOURCE_AUTO
  var packageDir: String = ""
  var mirror: String = ""
  var failFast: Boolean = false
  var downloadTimeoutSeconds: Int = 1800
  var uninstallPurgeDataDefault: Boolean = false

  fun autoInstallKinds(): List<String> =
    autoInstall
      .split(',', ';', ' ')
      .map { it.trim().lowercase() }
      .filter { it.isNotBlank() }
      .distinct()

  fun wantsLocal(): Boolean = sourceMode == SOURCE_LOCAL || sourceMode == SOURCE_AUTO

  fun wantsOnline(): Boolean = sourceMode == SOURCE_ONLINE || sourceMode == SOURCE_AUTO

  companion object {
    const val SOURCE_AUTO = "auto"
    const val SOURCE_ONLINE = "online"
    const val SOURCE_LOCAL = "local"
    val SOURCE_MODES = setOf(SOURCE_AUTO, SOURCE_ONLINE, SOURCE_LOCAL)
  }
}
