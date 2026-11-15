package org.lantai.infrastructure.plugin

import org.lantai.domain.model.ApiToken
import org.lantai.domain.model.PluginInstance
import org.lantai.domain.persistence.ApiTokenRepository
import org.lantai.domain.persistence.LantaiUserRepository
import org.lantai.infrastructure.security.apitoken.ApiTokenGenerator
import org.springframework.stereotype.Service
import java.time.LocalDateTime

@Service
class PluginTokenService(
  private val tokens: ApiTokenRepository,
  private val users: LantaiUserRepository,
  private val generator: ApiTokenGenerator,
) {
  fun issue(
    pluginId: String,
    ownerId: String,
  ): ApiToken {
    val name = tokenName(pluginId)
    tokens.findAll().firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { return it }
    val token =
      ApiToken(
        name = name,
        ownerId = resolveOwner(ownerId),
        tokenValue = generator.generate(),
        scopes = emptySet(),
      )
    tokens.insert(token)
    return token
  }

  fun valueOf(instance: PluginInstance): String? =
    instance.tokenId?.let { id -> tokens.findByIdOrNull(id)?.tokenValue?.takeIf { it.isNotBlank() } }

  fun syncScopes(
    instance: PluginInstance,
    capabilities: Collection<String>,
  ) {
    val tokenId = instance.tokenId ?: return
    val token = tokens.findByIdOrNull(tokenId) ?: return
    val scopes = scopesOf(capabilities)
    if (token.scopes == scopes) return
    tokens.update(token.copy(scopes = scopes, lastModifiedDate = LocalDateTime.now()))
  }

  fun revoke(instance: PluginInstance) {
    instance.tokenId?.let { tokens.deleteById(it) }
  }

  fun revokeByPluginId(pluginId: String) {
    val name = tokenName(pluginId)
    tokens.findAll().filter { it.name.equals(name, ignoreCase = true) }.forEach { tokens.deleteById(it.id) }
  }

  fun scopesOf(capabilities: Collection<String>): Set<ApiToken.Scope> =
    capabilities
      .mapNotNull { capability ->
        val label = capability.trim().replace('.', ':')
        ApiToken.Scope.entries.firstOrNull { it.label == label }
      }.toSet()

  private fun tokenName(pluginId: String): String = "$TOKEN_NAME_PREFIX$pluginId"

  private fun resolveOwner(ownerId: String): String {
    if (ownerId.isNotBlank() && users.findByIdOrNull(ownerId) != null) return ownerId
    return users.findAll().firstOrNull { it.isAdmin }?.id
      ?: throw PluginRequestException("签发插件令牌需要至少一个管理员账号")
  }

  companion object {
    const val TOKEN_NAME_PREFIX = "plugin:"
    const val SYSTEM_OWNER = "system"
  }
}
