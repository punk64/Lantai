package org.lantai.infrastructure.plugin

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.nio.file.Paths

@Component
class PluginPaths(
  @param:Value($$"${lantai.config-dir:}") private val configDir: String,
) {
  fun root(): Path {
    val base =
      if (configDir.isNotBlank()) {
        Paths.get(configDir).toAbsolutePath().normalize()
      } else {
        Paths.get(System.getProperty("user.home"), ".lantai").toAbsolutePath().normalize()
      }
    return base.resolve("plugins")
  }

  fun packageDir(
    pluginId: String,
    version: String,
  ): Path = root().resolve(pluginId).resolve(version).normalize()

  fun stagingRoot(): Path = root().resolve("_staging").normalize()

  fun stagingDir(suffix: String): Path = stagingRoot().resolve(suffix).normalize()

  fun downloadDir(): Path = root().resolve("_downloads").normalize()
}
