package org.lantai.domain.model

import com.github.f4b6a3.tsid.TsidCreator
import java.time.LocalDateTime

data class PluginRepository(
  val url: String,
  val name: String,
  val enabled: Boolean = true,
  val lastSyncDate: LocalDateTime? = null,
  val lastSyncMessage: String? = null,
  val id: String = TsidCreator.getTsid256().toString(),
  override val createdDate: LocalDateTime = LocalDateTime.now(),
  override val lastModifiedDate: LocalDateTime = createdDate,
) : Auditable

data class PluginInstance(
  val pluginId: String,
  val name: String,
  val version: String,
  val apiVersion: Int,
  val mode: PluginMode,
  val runtimeKind: String,
  val entry: String,
  val homeDir: String,
  val endpoint: String? = null,
  val port: Int? = null,
  val hostKey: String? = null,
  val tokenId: String? = null,
  val state: PluginState = PluginState.PENDING,
  val statusMessage: String? = null,
  val capabilities: String = "[]",
  val manifest: String = "{}",
  val settings: String = "{}",
  val memoryMb: Int = 0,
  val enabled: Boolean = true,
  val pid: Int? = null,
  val lastHealthDate: LocalDateTime? = null,
  val lastUsedDate: LocalDateTime? = null,
  val id: String = TsidCreator.getTsid256().toString(),
  override val createdDate: LocalDateTime = LocalDateTime.now(),
  override val lastModifiedDate: LocalDateTime = createdDate,
) : Auditable {
  fun isUsable(): Boolean = enabled && (mode == PluginMode.MANAGED || !endpoint.isNullOrBlank())
}

data class PluginGrant(
  val pluginId: String,
  val capability: String,
  val isDefault: Boolean = false,
  val grantedBy: String? = null,
  val id: String = TsidCreator.getTsid256().toString(),
  override val createdDate: LocalDateTime = LocalDateTime.now(),
  override val lastModifiedDate: LocalDateTime = createdDate,
) : Auditable

data class PluginCredential(
  val pluginId: String,
  val name: String,
  val value: String,
  val id: String = TsidCreator.getTsid256().toString(),
  override val createdDate: LocalDateTime = LocalDateTime.now(),
  override val lastModifiedDate: LocalDateTime = createdDate,
) : Auditable

data class PluginKv(
  val pluginId: String,
  val key: String,
  val value: String,
  val id: String = TsidCreator.getTsid256().toString(),
  override val createdDate: LocalDateTime = LocalDateTime.now(),
  override val lastModifiedDate: LocalDateTime = createdDate,
) : Auditable

data class PluginKvEntry(
  val key: String,
  val size: Long,
  val lastModifiedDate: LocalDateTime?,
)

data class PluginTaskSpec(
  val name: String,
  val cron: String,
  val endpoint: String,
  val timeoutSeconds: Int,
)

enum class PluginMode {
  MANAGED,
  EXTERNAL,
}

enum class PluginState {
  PENDING,
  READY,
  IDLE,
  UNAVAILABLE,
  AUTH_FAILED,
  INCOMPATIBLE,
  DISABLED,
}
