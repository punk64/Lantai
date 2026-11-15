package org.lantai.infrastructure.web

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.awt.Desktop
import java.awt.GraphicsEnvironment
import java.net.URI

private val logger = KotlinLogging.logger {}

@Component
class OpenBrowserOnReady(
  private val serverSettings: WebServerEffectiveSettings,
) {
  @EventListener(ApplicationReadyEvent::class)
  fun onReady() {
    if (System.getProperty("lantai.desktop.open-browser") != "true") return
    if (GraphicsEnvironment.isHeadless()) return
    val port = serverSettings.effectiveServerPort ?: return
    val url = "http://localhost:$port${serverSettings.effectiveServletContextPath}"
    runCatching { Desktop.getDesktop().browse(URI(url)) }
      .onFailure { logger.warn(it) { "Unable to open $url in the default browser" } }
  }
}
