package org.lantai.infrastructure.metadata

import org.lantai.domain.model.Author

private val ROLE_TAIL =
  Regex(
    """\s*[（(\[【]\s*(作者|著者|著|原著|原作|绘者|繪者|绘|繪|译者|譯者|翻译|翻譯|编者|編者|writer|author|artist|illustrator|translator|editor|story|art)\s*[)）\]】]\s*$""",
    RegexOption.IGNORE_CASE,
  )

private val BRACKET_PREFIX = Regex("""^\s*[【\[（(]\s*([^】\]）)]{1,8})\s*[】\]）)]\s*""")

private val SPACES = Regex("""\s{2,}""")

private val REGION_ALIASES: Map<String, String> =
  mapOf(
    "美" to "US",
    "美国" to "US",
    "美利坚" to "US",
    "日" to "JP",
    "日本" to "JP",
    "英" to "GB",
    "英国" to "GB",
    "法" to "FR",
    "法国" to "FR",
    "德" to "DE",
    "德国" to "DE",
    "俄" to "RU",
    "俄罗斯" to "RU",
    "苏联" to "RU",
    "韩" to "KR",
    "韩国" to "KR",
    "南韩" to "KR",
    "意" to "IT",
    "意大利" to "IT",
    "西" to "ES",
    "西班牙" to "ES",
    "加" to "CA",
    "加拿大" to "CA",
    "澳大利亚" to "AU",
    "澳洲" to "AU",
    "巴西" to "BR",
    "印度" to "IN",
    "荷兰" to "NL",
    "瑞士" to "CH",
    "瑞典" to "SE",
    "波兰" to "PL",
    "墨西哥" to "MX",
    "阿根廷" to "AR",
    "土耳其" to "TR",
    "以色列" to "IL",
    "泰国" to "TH",
    "越南" to "VN",
    "新加坡" to "SG",
    "马来西亚" to "MY",
    "印尼" to "ID",
    "印度尼西亚" to "ID",
    "菲律宾" to "PH",
    "蒙古" to "MN",
    "中国" to "CN",
    "中国大陆" to "CN",
    "中" to "CN",
    "台湾" to "TW",
    "中国台湾" to "TW",
    "香港" to "HK",
    "中国香港" to "HK",
    "澳门" to "MO",
    "中国澳门" to "MO",
  )

private val TRADITIONAL =
  mapOf(
    '國' to '国',
    '灣' to '湾',
    '臺' to '台',
    '韓' to '韩',
    '賓' to '宾',
    '蘭' to '兰',
    '亞' to '亚',
    '羅' to '罗',
    '門' to '门',
    '馬' to '马',
  )

object AuthorNameCleaner {
  fun clean(raw: String): String {
    var value = raw.trim()
    if (value.isEmpty()) return ""
    value = ROLE_TAIL.replace(value, "").trim()
    value = stripNationality(value)
    return SPACES.replace(value, " ").trim()
  }

  fun cleanAll(raw: List<String>): List<String> = raw.map { clean(it) }.filter { it.isNotBlank() }.distinct()

  fun cleanAuthors(raw: List<Author>): List<Author> =
    raw
      .map { Author(clean(it.name), it.role) }
      .filter { it.name.isNotBlank() }
      .distinctBy { it.name to it.role }

  fun regionOf(raw: String): String {
    val value = raw.trim()
    if (value.isEmpty()) return ""
    val match = BRACKET_PREFIX.find(value) ?: return ""
    return REGION_ALIASES[normalizeKey(match.groupValues[1])].orEmpty()
  }

  fun regionOfAuthors(authors: List<String>): String {
    authors.forEach { name ->
      val region = regionOf(name)
      if (region.isNotBlank()) return region
    }
    return ""
  }

  fun regionOfAuthorList(authors: List<Author>): String = regionOfAuthors(authors.map { it.name })

  private fun stripNationality(value: String): String {
    val match = BRACKET_PREFIX.find(value) ?: return value
    if (REGION_ALIASES[normalizeKey(match.groupValues[1])] == null) return value
    return value.substring(match.range.last + 1).trim()
  }

  private fun normalizeKey(raw: String): String =
    raw.filterNot { it.isWhitespace() }.map { TRADITIONAL[it] ?: it }.joinToString("")
}
