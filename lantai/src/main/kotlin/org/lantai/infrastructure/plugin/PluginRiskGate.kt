package org.lantai.infrastructure.plugin

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime

@Service
class PluginRiskGate(
  private val paths: PluginPaths,
) {
  private val mapper = ObjectMapper()

  @Synchronized
  fun acknowledged(): Boolean = Files.isRegularFile(file())

  @Synchronized
  fun acknowledge() {
    val target = file()
    Files.createDirectories(target.parent)
    val tmp = target.resolveSibling(target.fileName.toString() + ".tmp")
    Files.write(tmp, mapper.writeValueAsBytes(mapOf("acknowledged" to true, "at" to LocalDateTime.now().toString())))
    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
  }

  private fun file(): Path = paths.root().parent.resolve(".plugin-risk-ack")
}
