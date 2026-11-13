package org.lantai.infrastructure.plugin

import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

@Service
class PluginPanelTicketService {
  data class Grant(
    val instanceId: String,
    val panelId: String,
    val sessionId: String,
    val expiresAt: Long,
  )

  private val random = SecureRandom()
  private val tickets = ConcurrentHashMap<String, Grant>()
  private val sessions = ConcurrentHashMap<String, Grant>()

  fun issue(
    instanceId: String,
    panelId: String,
    sessionId: String,
  ): String {
    sweep()
    val value = token()
    tickets[value] = Grant(instanceId, panelId, sessionId, now() + TICKET_TTL_MS)
    return value
  }

  fun peek(
    value: String,
    instanceId: String,
    sessionId: String,
    panelId: String?,
  ): Grant? {
    val grant = tickets[value] ?: return null
    if (grant.expiresAt < now()) return null
    if (grant.instanceId != instanceId || grant.sessionId != sessionId) return null
    if (!panelId.isNullOrBlank() && grant.panelId != panelId) return null
    return grant
  }

  fun consume(
    value: String,
    instanceId: String,
    sessionId: String,
    panelId: String?,
  ): Grant? {
    val grant = tickets.remove(value) ?: return null
    if (grant.expiresAt < now()) return null
    if (grant.instanceId != instanceId || grant.sessionId != sessionId) return null
    if (!panelId.isNullOrBlank() && grant.panelId != panelId) return null
    return grant
  }

  fun openSession(grant: Grant): String {
    val value = token()
    sessions[value] = grant.copy(expiresAt = now() + SESSION_TTL_MS)
    return value
  }

  fun verify(
    value: String,
    instanceId: String,
    sessionId: String,
  ): Grant? {
    val grant = sessions[value] ?: return null
    if (grant.expiresAt < now()) {
      sessions.remove(value)
      return null
    }
    if (grant.instanceId != instanceId || grant.sessionId != sessionId) return null
    return grant
  }

  fun revokeInstance(instanceId: String) {
    tickets.entries.removeIf { it.value.instanceId == instanceId }
    sessions.entries.removeIf { it.value.instanceId == instanceId }
  }

  private fun token(): String {
    val bytes = ByteArray(32)
    random.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
  }

  private fun sweep() {
    val current = now()
    if (tickets.size > SWEEP_THRESHOLD) tickets.entries.removeIf { it.value.expiresAt < current }
    if (sessions.size > SWEEP_THRESHOLD) sessions.entries.removeIf { it.value.expiresAt < current }
  }

  private fun now(): Long = System.currentTimeMillis()

  companion object {
    private const val SESSION_TTL_MS = 8 * 60 * 60 * 1000L
    private const val TICKET_TTL_MS = 8 * 60 * 60 * 1000L
    private const val SWEEP_THRESHOLD = 256
    const val COOKIE_PREFIX = "lantai_panel_"
  }
}
