package org.lantai.infrastructure.plugin

import org.lantai.domain.persistence.PluginStore
import org.lantai.infrastructure.security.apitoken.TokenCipher
import org.springframework.stereotype.Service
import java.time.LocalDateTime

@Service
class PluginCredentialService(
  private val store: PluginStore,
  private val cipher: TokenCipher,
) {
  fun list(pluginId: String): List<PluginCredentialSummary> =
    store.listCredentials(pluginId).map {
      PluginCredentialSummary(
        name = it.name,
        size = it.value.toByteArray(Charsets.UTF_8).size.toLong(),
        lastModifiedDate = it.lastModifiedDate,
      )
    }

  fun get(
    pluginId: String,
    name: String,
  ): String? {
    val stored = store.findCredentialOrNull(pluginId, requireName(name)) ?: return null
    return cipher.decrypt(stored.value) ?: stored.value
  }

  fun put(
    pluginId: String,
    name: String,
    value: String,
  ) {
    val key = requireName(name)
    if (value.isEmpty()) throw PluginRequestException("凭据值不能为空")
    if (value.toByteArray(Charsets.UTF_8).size > MAX_VALUE_BYTES) throw PluginQuotaException("凭据值不得超过 $MAX_VALUE_BYTES 字节")
    store.upsertCredential(pluginId, key, cipher.encrypt(value))
  }

  fun delete(
    pluginId: String,
    name: String,
  ): Boolean = store.deleteCredential(pluginId, requireName(name))

  private fun requireName(raw: String): String {
    val name = raw.trim()
    if (name.length !in 1..MAX_NAME_LENGTH) throw PluginRequestException("凭据名长度须在 1~$MAX_NAME_LENGTH 之间")
    if (!NAME_PATTERN.matches(name)) throw PluginRequestException("凭据名只允许字母数字开头，可含点、下划线、连字符")
    return name
  }

  private companion object {
    private const val MAX_NAME_LENGTH = 128
    private const val MAX_VALUE_BYTES = 4096
    private val NAME_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")
  }
}

data class PluginCredentialSummary(
  val name: String,
  val size: Long,
  val lastModifiedDate: LocalDateTime?,
)
