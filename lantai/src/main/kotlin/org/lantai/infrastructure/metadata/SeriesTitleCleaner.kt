package org.lantai.infrastructure.metadata

private val SEGMENT = Regex("""\[([^\[\]]+)]""")

private val VOLUME =
  Regex("""(?i)^(vol\.?|v|ch\.?|chapter)?\s*\d+\s*([-~]\s*(vol\.?\s*)?\d+)?$""")

private val VOLUME_CN = Regex("""^(第\s*\d+\s*[卷册話话]|全\s*\d+\s*[卷册]|\d+\s*[卷册話话]|\d+\s*完|\d+\s*集)$""")

private val STATE_WORDS =
  setOf(
    "完結", "完结", "連載中", "连载中", "全彩", "彩漫", "汉化", "漢化", "电子版", "電子版",
    "生肉", "熟肉", "单行本", "單行本", "文库", "文庫", "全集", "套装", "套裝", "日漫", "韩漫",
    "韓漫", "国漫", "國漫", "高清", "完整版", "未删减", "無修", "无修", "简体", "繁体", "繁體",
  )

private val LATIN_ONLY = Regex("""^[A-Za-z0-9 ._+\-]{1,20}$""")

private val CJK = Regex("""[\u3040-\u30ff\u3400-\u9fff\uff66-\uff9f]""")

private val SPACES = Regex("""\s{2,}""")

object SeriesTitleCleaner {
  fun clean(
    raw: String,
    authors: Set<String> = emptySet(),
  ): String? {
    val value = raw.trim()
    if (value.length < 4 || !value.startsWith("[")) return null
    val segments = SEGMENT.findAll(value).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()
    if (segments.size < 2) return null
    val outside = SEGMENT.replace(value, "").trim()
    val outsideNoise = outside.isEmpty() || isVolume(outside) || STATE_WORDS.contains(normalize(outside))
    if (!outsideNoise && outside.length * 5 > value.length) return null
    val hasCjk = segments.any { CJK.containsMatchIn(it) }
    val authorKeys = authors.map { normalize(it) }.filter { it.isNotEmpty() }.toSet()
    val kept =
      segments.filterNot { segment ->
        isVolume(segment) ||
          STATE_WORDS.contains(normalize(segment)) ||
          (hasCjk && LATIN_ONLY.matches(segment) && !CJK.containsMatchIn(segment)) ||
          (authorKeys.isNotEmpty() && authorKeys.contains(normalize(segment)))
      }
    if (kept.isEmpty()) return null
    val best =
      if (kept.size == 1) {
        kept[0]
      } else {
        val ranked = kept.sortedByDescending { it.length }
        val head = ranked[0]
        if (head.length < 3 || head.length * 2 < ranked[1].length * 3) return null
        head
      }
    val out = SPACES.replace(best, " ").trim()
    if (out.isEmpty() || out == value) return null
    return out
  }

  private fun isVolume(segment: String): Boolean = VOLUME.matches(segment) || VOLUME_CN.matches(segment)

  private fun normalize(raw: String): String = raw.filterNot { it.isWhitespace() }.lowercase()
}
