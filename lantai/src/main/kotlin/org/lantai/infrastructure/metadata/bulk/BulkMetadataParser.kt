package org.lantai.infrastructure.metadata.bulk

data class BulkEntry(
  val index: Int,
  val seriesId: String,
  val title: String? = null,
  val status: String? = null,
  val publisher: String? = null,
  val language: String? = null,
  val year: Int? = null,
  val genres: List<String>? = null,
  val summary: String? = null,
)

data class BulkSheetRow(
  val index: Int,
  val seriesId: String,
  val name: String,
  val bookCount: Int,
)

data class BulkFieldChange(
  val field: String,
  val from: String,
  val to: String,
  val locked: Boolean,
  val applied: Boolean,
)

data class BulkItemResult(
  val seriesId: String,
  val seriesName: String,
  val ok: Boolean = true,
  val changes: List<BulkFieldChange> = emptyList(),
  val notes: List<String> = emptyList(),
)

data class BulkResult(
  val dryRun: Boolean,
  val total: Int,
  val applied: Int,
  val skipped: Int,
  val failed: Int,
  val items: List<BulkItemResult>,
)

object BulkMetadataParser {
  const val TITLE = "标题"
  const val STATUS = "状态"
  const val PUBLISHER = "出版社"
  const val LANGUAGE = "语言"
  const val YEAR = "年份"
  const val GENRES = "风格"
  const val SUMMARY = "简介"

  private val FIELDS = listOf(TITLE, STATUS, PUBLISHER, LANGUAGE, YEAR, GENRES, SUMMARY)
  private val HEADER = Regex("""^\[(\d+)\]\s+(\S+)""")
  private val FIELD = Regex("""^(${FIELDS.joinToString("|")})\s*[:：]\s*(.*)$""")
  private val REFERENCE = Regex("""^(当前名称|册数)\s*[:：]""")
  private val YEAR_PATTERN = Regex("""(\d{4})""")

  private val STATUS_MAP =
    mapOf(
      "连载中" to "ONGOING",
      "连载" to "ONGOING",
      "在载" to "ONGOING",
      "完结" to "ENDED",
      "已完结" to "ENDED",
      "休载" to "HIATUS",
      "暂停" to "HIATUS",
      "中止" to "ABANDONED",
      "腰斩" to "ABANDONED",
      "弃坑" to "ABANDONED",
    )

  fun render(
    rows: List<BulkSheetRow>,
    stamp: String,
  ): String =
    buildString {
      appendLine("# 兰台元数据回填单 · $stamp")
      appendLine("# 每个系列以 [序号] 系列ID 开头，保留该行原样")
      appendLine("# 只填需要修改的字段，留空或不写表示不覆盖")
      appendLine("# 状态可填：连载中 / 完结 / 休载 / 中止，也可填英文原值")
      appendLine("# 风格用逗号分隔，简介可多行")
      appendLine()
      rows.forEach { row ->
        appendLine("[${row.index}] ${row.seriesId}")
        appendLine("当前名称: ${row.name}")
        appendLine("册数: ${row.bookCount}")
        FIELDS.forEach { appendLine("$it:") }
        appendLine()
      }
    }

  fun parse(text: String): List<BulkEntry> {
    val entries = mutableListOf<BulkEntry>()
    var index = -1
    var seriesId: String? = null
    val buffer = linkedMapOf<String, StringBuilder>()

    fun flush() {
      val id = seriesId
      if (index > 0 && !id.isNullOrBlank()) entries += buildEntry(index, id, buffer)
      buffer.clear()
    }

    text.lineSequence().forEach { raw ->
      val line = raw.trimEnd()
      val header = HEADER.find(line)
      if (header != null) {
        flush()
        index = header.groupValues[1].toIntOrNull() ?: -1
        seriesId = header.groupValues[2]
        return@forEach
      }
      if (line.isBlank() || line.trimStart().startsWith("#")) return@forEach
      if (REFERENCE.containsMatchIn(line)) return@forEach
      val field = FIELD.find(line)
      if (field != null) {
        buffer[field.groupValues[1]] = StringBuilder(field.groupValues[2])
        return@forEach
      }
      val lastKey = buffer.keys.lastOrNull() ?: return@forEach
      val existing = buffer.getValue(lastKey)
      if (existing.isNotEmpty()) existing.append('\n')
      existing.append(line.trim())
    }
    flush()
    return entries
  }

  fun normalizeStatus(raw: String): String? {
    val value = raw.trim()
    if (value.isEmpty()) return null
    STATUS_MAP[value]?.let { return it }
    val upper = value.uppercase()
    return upper.takeIf { it in setOf("ONGOING", "ENDED", "ABANDONED", "HIATUS") }
  }

  private fun buildEntry(
    index: Int,
    seriesId: String,
    buffer: Map<String, StringBuilder>,
  ): BulkEntry {
    fun value(key: String): String? = buffer[key]?.toString()?.trim()?.takeIf { it.isNotEmpty() }

    return BulkEntry(
      index = index,
      seriesId = seriesId,
      title = value(TITLE),
      status = value(STATUS),
      publisher = value(PUBLISHER),
      language = value(LANGUAGE),
      year = value(YEAR)?.let { YEAR_PATTERN.find(it)?.groupValues?.get(1)?.toIntOrNull() },
      genres =
        value(GENRES)
          ?.split(',', '，', '、', '/', ';', '；')
          ?.map { it.trim() }
          ?.filter { it.isNotEmpty() }
          ?.takeIf { it.isNotEmpty() },
      summary = value(SUMMARY),
    )
  }
}
