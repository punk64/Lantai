package org.lantai.infrastructure.metadata.bulk

import org.lantai.domain.model.Dimension
import org.lantai.domain.model.MarkSelectedPreference
import org.lantai.domain.model.ThumbnailSeries
import org.lantai.domain.persistence.SeriesRepository
import org.lantai.domain.service.SeriesLifecycle
import org.lantai.infrastructure.image.ImageAnalyzer
import org.lantai.infrastructure.mediacontainer.ContentDetector
import org.springframework.stereotype.Service
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Service
class BulkPackageService(
  private val bulkMetadataService: BulkMetadataService,
  private val seriesRepository: SeriesRepository,
  private val seriesLifecycle: SeriesLifecycle,
  private val contentDetector: ContentDetector,
  private val imageAnalyzer: ImageAnalyzer,
) {
  fun submit(
    packageName: String,
    stream: InputStream,
    dryRun: Boolean,
  ): BulkPackageResult {
    val content = readPackage(stream)
    val text = content.text ?: throw IllegalArgumentException("压缩包里没有 metadata.txt")
    val entries = BulkMetadataParser.parse(text)
    val metadata = bulkMetadataService.submit(text, dryRun)
    val indexById = entries.flatMap { listOf(it.seriesId to it, it.seriesId.lowercase() to it) }.toMap()
    val indexByNumber = entries.associateBy { it.index }

    val covers = mutableListOf<BulkCoverResult>()
    val ignored = mutableListOf<String>()
    content.covers.forEach { (key, file) ->
      val entry = indexById[key] ?: key.toIntOrNull()?.let { indexByNumber[it] }
      if (entry == null) {
        ignored += file.name
        return@forEach
      }
      val series = seriesRepository.findByIdOrNull(entry.seriesId)
      if (series == null) {
        covers += BulkCoverResult(entry.seriesId, entry.seriesId, file.name, file.bytes.size.toLong(), false, "系列不存在")
        return@forEach
      }
      if (dryRun) {
        covers += BulkCoverResult(series.id, series.name, file.name, file.bytes.size.toLong(), true, "预览")
        return@forEach
      }
      val applied =
        runCatching {
          val mediaType = contentDetector.detectMediaType(ByteArrayInputStream(file.bytes).buffered())
          if (!contentDetector.isImage(mediaType)) throw IllegalArgumentException("不是图片：${file.name}")
          seriesLifecycle.addThumbnailForSeries(
            ThumbnailSeries(
              seriesId = series.id,
              thumbnail = file.bytes,
              type = ThumbnailSeries.Type.USER_UPLOADED,
              fileSize = file.bytes.size.toLong(),
              mediaType = mediaType,
              dimension = imageAnalyzer.getDimension(ByteArrayInputStream(file.bytes).buffered()) ?: Dimension(0, 0),
            ),
            MarkSelectedPreference.YES,
          )
        }
      covers +=
        BulkCoverResult(
          series.id,
          series.name,
          file.name,
          file.bytes.size.toLong(),
          applied.isSuccess,
          applied.exceptionOrNull()?.message,
        )
    }

    return BulkPackageResult(dryRun, packageName, metadata, covers, ignored)
  }

  fun template(): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
      zip.putNextEntry(ZipEntry("metadata.txt"))
      zip.write(TEMPLATE_TEXT.toByteArray(Charsets.UTF_8))
      zip.closeEntry()
      zip.putNextEntry(ZipEntry("covers/README.txt"))
      zip.write(COVERS_README.toByteArray(Charsets.UTF_8))
      zip.closeEntry()
    }
    return out.toByteArray()
  }

  private fun readPackage(stream: InputStream): PackageContent {
    var text: String? = null
    val covers = LinkedHashMap<String, CoverFile>()
    var total = 0L
    var count = 0
    ZipInputStream(stream.buffered()).use { zip ->
      var entry: ZipEntry? = zip.nextEntry
      while (entry != null) {
        count++
        if (count > MAX_ENTRIES) throw IllegalArgumentException("压缩包条目超过 $MAX_ENTRIES，已拒绝")
        val name = entry.name.replace('\\', '/')
        if (!entry.isDirectory && !name.startsWith("__MACOSX/")) {
          val lower = name.lowercase()
          val base = name.substringAfterLast('/')
          when {
            lower.endsWith("metadata.txt") -> {
              if (text == null) text = readEntry(zip, entry).toString(Charsets.UTF_8).removePrefix("\uFEFF")
            }
            lower.contains("covers/") || name.count { it == '/' } == 0 -> {
              val ext = base.substringAfterLast('.', "").lowercase()
              if (ext in IMAGE_EXTENSIONS) {
                val bytes = readEntry(zip, entry)
                val key = base.substringBeforeLast('.').lowercase()
                covers[key] = CoverFile(base, bytes)
              }
            }
          }
        }
        total += entry.size.coerceAtLeast(0)
        if (total > MAX_TOTAL) throw IllegalArgumentException("压缩包解压后超过 ${MAX_TOTAL / 1024 / 1024}MB，已拒绝")
        zip.closeEntry()
        entry = zip.nextEntry
      }
    }
    return PackageContent(text, covers)
  }

  private fun readEntry(
    zip: ZipInputStream,
    entry: ZipEntry,
  ): ByteArray {
    val buffer = ByteArrayOutputStream()
    val chunk = ByteArray(8192)
    var size = 0L
    while (true) {
      val read = zip.read(chunk)
      if (read <= 0) break
      size += read
      if (size > MAX_ENTRY) throw IllegalArgumentException("${entry.name} 超过 ${MAX_ENTRY / 1024 / 1024}MB，已拒绝")
      buffer.write(chunk, 0, read)
    }
    return buffer.toByteArray()
  }

  private data class PackageContent(
    val text: String?,
    val covers: Map<String, CoverFile>,
  )

  private data class CoverFile(
    val name: String,
    val bytes: ByteArray,
  )

  companion object {
    private const val MAX_ENTRIES = 2000
    private const val MAX_ENTRY = 8L * 1024 * 1024
    private const val MAX_TOTAL = 128L * 1024 * 1024
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

    private val TEMPLATE_TEXT =
      """
      |# 兰台批量元数据 · 填写说明
      |#
      |# 每条以 [序号] 系列ID 开头，序号与封面文件名对应
      |# 字段留空表示不覆盖当前值；被锁定的字段会被跳过
      |# 支持的字段：标题 / 状态 / 出版社 / 语言 / 年份 / 风格 / 简介
      |# 状态可写：连载 / 完结 / 休载，或 ONGOING / ENDED / HIATUS
      |# 风格多个用逗号分隔；简介可多行
      |#
      |# 封面放在 covers/ 目录，命名二选一：
      |#   covers/系列ID.jpg     推荐
      |#   covers/序号.jpg       与上方 [序号] 对应
      |# 支持 jpg / jpeg / png / webp / gif，单张不超过 8MB
      |
      |[1] 替换成系列ID
      |标题:
      |状态:
      |出版社:
      |语言:
      |年份:
      |风格:
      |简介:
      |
      """.trimMargin()

    private val COVERS_README =
      """
      |把封面图放在本目录，文件名二选一：
      |
      |  系列ID.jpg    推荐，与 metadata.txt 里的系列ID同名
      |  序号.jpg      与 metadata.txt 里的 [序号] 对应
      |
      |支持 jpg / jpeg / png / webp / gif，单张不超过 8MB。
      |写入后会替换该系列当前封面并自动选中。
      |
      """.trimMargin()
  }
}

data class BulkPackageResult(
  val dryRun: Boolean,
  val packageFile: String,
  val metadata: BulkResult,
  val covers: List<BulkCoverResult>,
  val ignored: List<String>,
)

data class BulkCoverResult(
  val seriesId: String,
  val seriesTitle: String,
  val fileName: String,
  val sizeBytes: Long,
  val applied: Boolean,
  val note: String? = null,
)
