package org.lantai.infrastructure.net

import java.net.URI

object OutboundUrlGuard {
  private val PRIVATE_PREFIX = listOf("127.", "10.", "192.168.", "169.254.", "0.", "fc00:", "fd00:", "fe80:")

  fun isPrivateHost(host: String): Boolean {
    val h = host.lowercase().trimEnd('.')
    if (h == "localhost" || h.endsWith(".localhost") || h == "::1" || h == "[::1]") return true
    if (PRIVATE_PREFIX.any { h.startsWith(it) }) return true
    val parts = h.split('.')
    if (parts.size == 4 && parts[0] == "172") {
      val second = parts[1].toIntOrNull() ?: return false
      return second in 16..31
    }
    return false
  }

  fun parse(raw: String): URI {
    val uri = runCatching { URI.create(raw.trim()) }.getOrElse { throw IllegalArgumentException("地址不合法") }
    val scheme = uri.scheme?.lowercase()
    if (scheme != "https" && scheme != "http") throw IllegalArgumentException("地址必须使用 http(s)")
    val host = uri.host?.lowercase()?.trimEnd('.') ?: throw IllegalArgumentException("地址不合法")
    if (isPrivateHost(host)) throw IllegalArgumentException("地址不允许指向内网")
    return uri
  }

  fun hostAllowed(host: String, allowed: Set<String>): Boolean {
    val h = host.lowercase().trimEnd('.')
    return allowed.any { it == h || (it.startsWith("*.") && h.length > it.length && h.endsWith(it.substring(1))) }
  }

  fun requireAllowed(raw: String, allowed: Set<String>): URI {
    val uri = parse(raw)
    val host = uri.host.lowercase().trimEnd('.')
    if (!hostAllowed(host, allowed)) throw IllegalArgumentException("地址不在允许的域名内：$host")
    return uri
  }
}
