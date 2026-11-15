package org.lantai.interfaces.apprunner

import io.github.oshai.kotlinlogging.KotlinLogging
import org.lantai.infrastructure.jooq.main.ServerSettingsDao
import org.lantai.infrastructure.plugin.PluginInstanceService
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class PluginPanelGrantRevoker(
  private val instances: PluginInstanceService,
  private val settings: ServerSettingsDao,
) : ApplicationRunner {
  override fun run(args: ApplicationArguments) {
    if (settings.getSettingByKey(MARKER_KEY, Boolean::class.java) == true) return
    var revoked = 0
    instances.list().forEach { instance ->
      runCatching {
        val current = instances.grants(instance.id)
        if (current.none { it.capability == PANEL_CAPABILITY }) return@runCatching
        val kept = current.filter { it.capability != PANEL_CAPABILITY }
        instances.updateGrants(
          instance.id,
          kept.map { it.capability },
          kept.firstOrNull { it.isDefault }?.capability ?: kept.firstOrNull()?.capability,
          null,
        )
        revoked++
      }.onFailure { logger.warn(it) { "撤销插件面板授权失败：${instance.pluginId}" } }
    }
    settings.saveSetting(MARKER_KEY, true)
    if (revoked > 0) logger.info { "插件面板权限已降级为未授权：$revoked 个实例" }
  }

  private companion object {
    private const val MARKER_KEY = "PLUGIN_PANEL_GRANT_REVOKED_V1"
    private const val PANEL_CAPABILITY = "ui.panel"
  }
}
