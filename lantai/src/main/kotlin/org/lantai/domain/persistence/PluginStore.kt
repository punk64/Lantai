package org.lantai.domain.persistence

import org.lantai.domain.model.PluginCredential
import org.lantai.domain.model.PluginGrant
import org.lantai.domain.model.PluginInstance
import org.lantai.domain.model.PluginKv
import org.lantai.domain.model.PluginKvEntry

interface PluginStore {
  fun insertInstance(instance: PluginInstance)

  fun updateInstance(instance: PluginInstance)

  fun findInstanceByIdOrNull(id: String): PluginInstance?

  fun findByPluginIdOrNull(pluginId: String): PluginInstance?

  fun findAllInstances(): List<PluginInstance>

  fun deleteInstance(id: String)

  fun findGrantsByPluginId(pluginId: String): List<PluginGrant>

  fun replaceGrants(pluginId: String, grants: List<PluginGrant>)

  fun findDefaultPluginId(capability: String): String?

  fun findProviders(capability: String): List<PluginInstance>

  fun deleteGrantsByPluginId(pluginId: String)

  fun findKvOrNull(pluginId: String, key: String): PluginKv?

  fun listKv(
    pluginId: String,
    page: Int,
    size: Int,
  ): List<PluginKvEntry>

  fun countKv(pluginId: String): Int

  fun upsertKv(pluginId: String, key: String, value: String)

  fun deleteKv(pluginId: String, key: String): Boolean

  fun kvTotalBytes(pluginId: String): Long

  fun listCredentials(pluginId: String): List<PluginCredential>

  fun findCredentialOrNull(
    pluginId: String,
    name: String,
  ): PluginCredential?

  fun upsertCredential(
    pluginId: String,
    name: String,
    value: String,
  )

  fun deleteCredential(
    pluginId: String,
    name: String,
  ): Boolean
}
