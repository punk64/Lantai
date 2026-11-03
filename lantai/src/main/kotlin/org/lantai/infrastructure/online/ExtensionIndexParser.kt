package org.lantai.infrastructure.online

import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

object ExtensionIndexParser {
  private const val FIELD_EXTENSION_STORE_EXTENSIONS = 101
  private val urlRegex = Regex("""https?://[!-~]*?(?=https?://|$)""")

  fun parse(raw: ByteArray): List<SuwayomiCatalogEntry> {
    val bytes = gunzip(raw)
    val root = readFields(bytes)
    val container = root.firstOrNull { it.number == FIELD_EXTENSION_STORE_EXTENSIONS && it.bytes != null }?.bytes
    val entries =
      if (container != null) {
        readFields(container).filter { it.number == 1 && it.bytes != null }.mapNotNull { it.bytes }
      } else {
        root.filter { it.number == 1 && it.bytes != null }.mapNotNull { it.bytes }
      }
    return entries.mapNotNull { toEntry(it) }
  }

  private fun toEntry(bytes: ByteArray): SuwayomiCatalogEntry? {
    val fields = runCatching { readFields(bytes) }.getOrNull() ?: return null
    val name = fields.text(1) ?: return null
    val pkgName = fields.text(2) ?: return null
    val versionName = fields.text(6) ?: fields.text(4) ?: ""
    val lang = collectLangs(fields)
    val versionCode = fields.firstOrNull { it.number == 5 }?.varint ?: 0L
    val urls = fields.filter { it.number == 3 }.flatMap { field -> urls(field) }
    val jarUrl = urls.firstOrNull { it.endsWith(".jar") }
    val apkUrl = urls.firstOrNull { it.endsWith(".apk") }
    val iconUrl = urls.firstOrNull { it.endsWith(".png") || it.endsWith(".jpg") || it.endsWith(".webp") }
    return SuwayomiCatalogEntry(
      pkgName = pkgName,
      name = name,
      versionName = versionName,
      lang = lang,
      versionCode = versionCode,
      jarUrl = jarUrl,
      apkUrl = apkUrl,
      iconUrl = iconUrl,
    )
  }

  private fun urls(field: Field): List<String> {
    val bytes = field.bytes ?: return emptyList()
    val nested = runCatching { readFields(bytes) }.getOrNull().orEmpty()
    val strings =
      nested
        .filter { it.wire == 2 && it.bytes != null }
        .mapNotNull { it.bytes?.let { raw -> String(raw, Charsets.UTF_8) } }
        .filter { it.isNotBlank() }
    val candidates = strings.ifEmpty { listOf(String(bytes, Charsets.UTF_8)) }
    return candidates.flatMap { urlRegex.findAll(it).map { match -> match.value }.toList() }
  }

  private fun collectLangs(fields: List<Field>): String {
    val langs =
      fields
        .filter { it.number == 8 && it.bytes != null }
        .mapNotNull { field ->
          runCatching { readFields(field.bytes!!) }.getOrNull()?.text(3)
        }.filter { it.isNotBlank() }
        .distinct()
        .sorted()
    return when {
      langs.isEmpty() -> ""
      langs.size <= 3 -> langs.joinToString(",")
      else -> langs.take(3).joinToString(",") + "…"
    }
  }

  private fun List<Field>.text(number: Int): String? =
    firstOrNull { it.number == number && it.bytes != null }?.bytes?.let { String(it, Charsets.UTF_8) }?.takeIf { it.isNotBlank() }

  private fun gunzip(raw: ByteArray): ByteArray {
    if (raw.size < 2 || raw[0] != 0x1f.toByte() || raw[1] != 0x8b.toByte()) return raw
    return GZIPInputStream(ByteArrayInputStream(raw)).use { it.readBytes() }
  }

  private data class Field(
    val number: Int,
    val wire: Int,
    val varint: Long?,
    val bytes: ByteArray?,
  )

  private fun readFields(buf: ByteArray): List<Field> {
    val result = mutableListOf<Field>()
    var index = 0
    while (index < buf.size) {
      val (key, afterKey) = readVarint(buf, index)
      index = afterKey
      val number = (key ushr 3).toInt()
      val wire = (key and 0x07).toInt()
      if (number == 0) break
      when (wire) {
        0 -> {
          val (value, next) = readVarint(buf, index)
          index = next
          result.add(Field(number, wire, value, null))
        }
        1 -> {
          if (index + 8 > buf.size) break
          index += 8
          result.add(Field(number, wire, null, null))
        }
        2 -> {
          val (length, next) = readVarint(buf, index)
          index = next
          val end = index + length.toInt()
          if (end > buf.size || end < index) break
          result.add(Field(number, wire, null, buf.copyOfRange(index, end)))
          index = end
        }
        5 -> {
          if (index + 4 > buf.size) break
          index += 4
          result.add(Field(number, wire, null, null))
        }
        else -> break
      }
    }
    return result
  }

  private fun readVarint(
    buf: ByteArray,
    start: Int,
  ): Pair<Long, Int> {
    var result = 0L
    var shift = 0
    var index = start
    while (index < buf.size) {
      val byte = buf[index].toInt() and 0xFF
      index++
      result = result or ((byte and 0x7F).toLong() shl shift)
      if (byte and 0x80 == 0) return result to index
      shift += 7
      if (shift > 63) break
    }
    throw IllegalArgumentException("malformed varint at $start")
  }
}
