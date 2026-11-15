package org.lantai.infrastructure.plugin

import org.lantai.domain.model.PluginKv
import org.lantai.domain.model.PluginKvEntry
import org.lantai.domain.persistence.PluginStore
import org.springframework.stereotype.Service
import java.time.LocalDateTime

@Service
class PluginKvService(
  private val store: PluginStore,
) {
  fun put(
    pluginId: String,
    key: String,
    value: String,
  ): PluginKvEntry {
    validateKey(key)
    val size = sizeOf(value)
    if (size > MAX_VALUE_BYTES) throw PluginQuotaException("单个键值不得超过 ${MAX_VALUE_BYTES / 1024} KB")
    val previous = store.findKvOrNull(pluginId, key)?.let { sizeOf(it.value) } ?: 0L
    val total = store.kvTotalBytes(pluginId) - previous + size
    if (total > MAX_TOTAL_BYTES) throw PluginQuotaException("插件存储超出配额（上限 ${MAX_TOTAL_BYTES / 1024 / 1024} MB）")
    store.upsertKv(pluginId, key, value)
    return PluginKvEntry(key = key, size = size, lastModifiedDate = LocalDateTime.now())
  }

  fun get(
    pluginId: String,
    key: String,
  ): PluginKv? {
    validateKey(key)
    return store.findKvOrNull(pluginId, key)
  }

  fun list(
    pluginId: String,
    page: Int,
    size: Int,
  ): List<PluginKvEntry> = store.listKv(pluginId, page.coerceAtLeast(0), size.coerceIn(1, MAX_PAGE_SIZE))

  fun count(pluginId: String): Int = store.countKv(pluginId)

  fun usage(pluginId: String): Long = store.kvTotalBytes(pluginId)

  fun delete(
    pluginId: String,
    key: String,
  ): Boolean {
    validateKey(key)
    return store.deleteKv(pluginId, key)
  }

  private fun validateKey(key: String) {
    if (key.isEmpty() || key.length > MAX_KEY_LENGTH) throw PluginRequestException("键名长度必须在 1~$MAX_KEY_LENGTH 之间")
    if (!KEY_PATTERN.matches(key)) throw PluginRequestException("键名只允许字母、数字与 . _ - :")
  }

  private fun sizeOf(value: String): Long = value.toByteArray(Charsets.UTF_8).size.toLong()

  companion object {
    const val MAX_VALUE_BYTES: Long = 1024L * 1024L
    const val MAX_TOTAL_BYTES: Long = 32L * 1024L * 1024L
    const val MAX_KEY_LENGTH: Int = 255
    const val MAX_PAGE_SIZE: Int = 500
    private val KEY_PATTERN = Regex("^[A-Za-z0-9._:-]+$")
  }
}

class PluginQuotaException(message: String) : RuntimeException(message)
