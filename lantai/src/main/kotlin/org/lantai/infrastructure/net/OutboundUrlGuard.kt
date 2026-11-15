package org.lantai.infrastructure.net

import java.net.InetAddress
import java.net.URI

object OutboundUrlGuard {
  private const val V4_PART = "(?:\\d{1,10}|0[xX][0-9a-fA-F]{1,8})"
  private val V4_LITERAL = Regex("^$V4_PART(\\.$V4_PART){0,3}$")
  private val V4_HEX = Regex("^0[xX][0-9a-fA-F]{1,8}$")

  fun isPrivateHost(host: String): Boolean {
    val h = normalize(host)
    if (h.isEmpty()) return true
    if (h == "localhost" || h.endsWith(".localhost")) return true
    parseIpv4(h)?.let { return isPrivateV4(it) }
    parseIpv6(h)?.let { return isPrivateV6(it) }
    return false
  }

  fun isPrivateAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) {
      return true
    }
    return when (address.address.size) {
      4 -> isPrivateV4(v4Of(address.address, 0))
      16 -> isPrivateV6(address.address)
      else -> true
    }
  }

  fun resolveAndCheck(host: String): List<InetAddress> {
    val h = normalize(host)
    if (isPrivateHost(h)) throw IllegalArgumentException("地址不允许指向内网")
    if (h.contains(':')) return emptyList()
    val addresses = runCatching { InetAddress.getAllByName(h) }.getOrElse { throw IllegalArgumentException("域名无法解析：$h") }
    if (addresses.isEmpty()) throw IllegalArgumentException("域名无法解析：$h")
    if (addresses.any { isPrivateAddress(it) }) throw IllegalArgumentException("地址不允许指向内网")
    return addresses.toList()
  }

  fun parse(raw: String): URI {
    val uri = runCatching { URI.create(raw.trim()) }.getOrElse { throw IllegalArgumentException("地址不合法") }
    val scheme = uri.scheme?.lowercase()
    if (scheme != "https" && scheme != "http") throw IllegalArgumentException("地址必须使用 http(s)")
    val host = uri.host?.lowercase()?.trimEnd('.') ?: throw IllegalArgumentException("地址不合法")
    if (isPrivateHost(host)) throw IllegalArgumentException("地址不允许指向内网")
    return uri
  }

  fun parseStrict(raw: String): URI {
    val uri = parse(raw)
    resolveAndCheck(uri.host ?: throw IllegalArgumentException("地址不合法"))
    return uri
  }

  fun hostAllowed(
    host: String,
    allowed: Set<String>,
  ): Boolean {
    val h = normalize(host)
    return allowed.any { it == h || (it.startsWith("*.") && h.length > it.length && h.endsWith(it.substring(1))) }
  }

  fun requireAllowed(
    raw: String,
    allowed: Set<String>,
  ): URI {
    val uri = parseStrict(raw)
    val host = normalize(uri.host ?: throw IllegalArgumentException("地址不合法"))
    if (!hostAllowed(host, allowed)) throw IllegalArgumentException("地址不在允许的域名内：$host")
    return uri
  }

  private fun normalize(host: String): String = host.lowercase().trim().removeSurrounding("[", "]").trimEnd('.')

  private fun v4Of(raw: ByteArray, offset: Int): Int =
    ((raw[offset].toInt() and 0xff) shl 24) or
      ((raw[offset + 1].toInt() and 0xff) shl 16) or
      ((raw[offset + 2].toInt() and 0xff) shl 8) or
      (raw[offset + 3].toInt() and 0xff)

  private fun isPrivateV4(value: Int): Boolean {
    val a = (value ushr 24) and 0xff
    val b = (value ushr 16) and 0xff
    return when {
      a == 0 || a == 10 || a == 127 -> true
      a == 100 && b in 64..127 -> true
      a == 169 && b == 254 -> true
      a == 172 && b in 16..31 -> true
      a == 192 && b == 168 -> true
      a == 192 && b == 0 && ((value ushr 8) and 0xff) == 0 -> true
      a == 198 && (b == 18 || b == 19) -> true
      a == 198 && b == 51 -> true
      a == 203 && b == 0 -> true
      a >= 224 -> true
      else -> false
    }
  }

  private fun isPrivateV6(raw: ByteArray): Boolean {
    if (raw.size != 16) return true
    if ((0 until 12).all { raw[it].toInt() == 0 }) return true
    if ((raw[0].toInt() and 0xfe) == 0xfc) return true
    if ((raw[0].toInt() and 0xff) == 0xfe && (raw[1].toInt() and 0xc0) == 0x80) return true
    if ((raw[0].toInt() and 0xff) == 0xff) return true
    val mapped = (0 until 10).all { raw[it].toInt() == 0 } && (raw[10].toInt() and 0xff) == 0xff && (raw[11].toInt() and 0xff) == 0xff
    if (mapped) return isPrivateV4(v4Of(raw, 12))
    val nat64 = (raw[0].toInt() and 0xff) == 0x00 && (raw[1].toInt() and 0xff) == 0x64 && (raw[2].toInt() and 0xff) == 0xff && (raw[3].toInt() and 0xff) == 0x9b
    if (nat64) return isPrivateV4(v4Of(raw, 12))
    return false
  }

  private fun parseIpv4(h: String): Int? {
    if (V4_HEX.matches(h)) return h.substring(2).toLongOrNull(16)?.takeIf { it <= 0xffffffffL }?.toInt()
    if (!V4_LITERAL.matches(h)) return null
    val parts = h.lowercase().split('.')
    val nums = parts.map { parsePart(it) ?: return null }
    if (nums.any { it > 0xffffffffL }) return null
    if (parts.size == 1) return nums[0].toInt()
    if (nums.dropLast(1).any { it > 255 }) return null
    val shift = 8 * (5 - parts.size)
    val last = nums.last()
    if (last >= (1L shl shift)) return null
    var value = 0L
    nums.dropLast(1).forEach { value = (value shl 8) or it }
    return ((value shl shift) or last).toInt()
  }

  private fun parsePart(part: String): Long? =
    if (part.startsWith("0x")) part.substring(2).toLongOrNull(16) else part.toLongOrNull()

  private fun parseIpv6(h: String): ByteArray? {
    if (!h.contains(':')) return null
    return runCatching { InetAddress.getByName(h).address }.getOrDefault(ByteArray(1))
  }
}
