package org.lantai.infrastructure.mediacontainer.txt

import io.github.oshai.kotlinlogging.KotlinLogging
import org.lantai.domain.model.MediaExtensionTxt
import org.lantai.domain.model.TxtChapter
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Locale
import kotlin.io.path.fileSize
import kotlin.io.path.inputStream

private val logger = KotlinLogging.logger {}

@Service
class TxtExtractor {
  private val gb18030 = Charset.forName("GB18030")
  private val big5 = Charset.forName("Big5")

  private val chapterPatterns =
    listOf(
      Regex("""第\s*[0-9０-９零〇一二三四五六七八九十百千万两]{1,12}\s*[章节節回卷篇集话話册](.{0,40})"""),
      Regex("""第\s*[0-9０-９零〇一二三四五六七八九十百千万两]{1,12}\s*部(?!分)(.{0,40})"""),
      Regex("""(?:Chapter|CHAPTER|chapter)\s*(?:[0-9]{1,4}|[IVXLCDM]{1,8})(.{0,40})"""),
      Regex("""(?:楔子|序章|序言|引子|前言|尾声|尾聲|后记|後記|番外|终章|終章)(.{0,30})"""),
      Regex("""正文"""),
      Regex("""(?:卷|部|册)[0-9０-９零〇一二三四五六七八九十百千万两]{1,6}(.{0,30})"""),
      Regex("""[0-9]{1,4}[、.．]\s*(.{0,40})"""),
    )

  private val rejectedLineEndings =
    charArrayOf(
      '。', '，', '？', '！', '；', '：', '、', '…', '”', '"',
      '.', ',', '?', '!', ';', ':', ']',
    )

  fun analyze(path: Path): MediaExtensionTxt {
    val totalBytes = path.fileSize()
    val head = readHead(path, DETECT_SIZE)
    val bomSize = detectBomSize(head)
    val charset = detectCharset(head)
    val unit = if (isUtf16(charset)) 2 else 1
    val littleEndian = charset.name().uppercase(Locale.ROOT).endsWith("LE")
    val chapters = mutableListOf<TxtChapter>()
    var lineStartByte = bomSize.toLong()
    var lineStartChar = 0L
    var pendingTitle: String? = null
    var pendingStartByte = bomSize.toLong()
    var pendingStartChar = 0L
    var contentSeen = false

    fun commit(
      endByte: Long,
      endChar: Long,
      startByte: Long,
      startChar: Long,
    ) {
      if (endByte <= startByte) return
      chapters.add(
        TxtChapter(
          index = chapters.size + 1,
          title = pendingTitle ?: DEFAULT_TITLE,
          byteStart = startByte,
          byteEnd = endByte,
          charStart = startChar,
          charCount = endChar - startChar,
        ),
      )
    }

    fun onLine(
      lineBytes: ByteArray,
      lineEndByte: Long,
      lineCharCount: Long,
    ) {
      val text = String(lineBytes, charset)
      if (text.isNotBlank()) {
        contentSeen = true
        if (isChapterTitle(text)) {
          if (pendingTitle != null) {
            commit(lineStartByte, lineStartChar, pendingStartByte, pendingStartChar)
          } else if (lineStartByte > bomSize.toLong()) {
            commit(lineStartByte, lineStartChar, bomSize.toLong(), 0)
          }
          pendingTitle = text.trim()
          pendingStartByte = lineStartByte
          pendingStartChar = lineStartChar
        }
      }
      lineStartChar += lineCharCount + 1
      lineStartByte = lineEndByte
    }

    FileChannel.open(path, StandardOpenOption.READ).use { channel ->
      val buffer = ByteBuffer.allocate(CHUNK_SIZE)
      val carry = ByteArrayOutputStream()
      var filePos = 0L
      while (true) {
        buffer.clear()
        val read = channel.read(buffer)
        if (read <= 0) break
        val chunk = buffer.array()
        var index = 0
        var segmentStart = if (filePos == 0L) bomSize else 0
        while (index + unit <= read) {
          val newlineEnd = newlineEndAt(chunk, index, read, unit, littleEndian)
          if (newlineEnd > 0) {
            carry.write(chunk, segmentStart, newlineEnd - segmentStart)
            val lineBytes = carry.toByteArray()
            val lineChars = String(lineBytes, charset).length.toLong()
            onLine(lineBytes, filePos + newlineEnd, lineChars)
            carry.reset()
            segmentStart = newlineEnd
            index = newlineEnd
          } else {
            index += unit
          }
        }
        if (read > segmentStart) carry.write(chunk, segmentStart, read - segmentStart)
        filePos += read
      }
      if (carry.size() > 0) {
        val lineBytes = carry.toByteArray()
        onLine(lineBytes, totalBytes, String(lineBytes, charset).length.toLong())
      }
    }

    if (pendingTitle != null) {
      commit(lineStartByte, lineStartChar, pendingStartByte, pendingStartChar)
    } else if (contentSeen) {
      chapters.add(
        TxtChapter(
          index = 1,
          title = DEFAULT_TITLE,
          byteStart = bomSize.toLong(),
          byteEnd = totalBytes,
          charStart = 0,
          charCount = lineStartChar,
        ),
      )
    }

    if (chapters.isEmpty()) {
      logger.warn { "No content found in txt file: $path" }
    }
    logger.info { "Analyzed txt: encoding=${charset.name()} chapters=${chapters.size} bytes=$totalBytes chars=$lineStartChar" }
    return MediaExtensionTxt(
      encoding = charset.name(),
      totalBytes = totalBytes,
      totalChars = lineStartChar,
      chapters = chapters,
    )
  }

  fun readChapter(
    path: Path,
    extension: MediaExtensionTxt,
    chapter: TxtChapter,
  ): String = String(readChapterBytes(path, chapter), Charset.forName(extension.encoding))

  fun readChapterBytes(
    path: Path,
    chapter: TxtChapter,
  ): ByteArray {
    val length = (chapter.byteEnd - chapter.byteStart).toInt()
    if (length <= 0) return ByteArray(0)
    FileChannel.open(path, StandardOpenOption.READ).use { channel ->
      channel.position(chapter.byteStart)
      val buffer = ByteBuffer.allocate(length)
      while (buffer.hasRemaining()) {
        if (channel.read(buffer) < 0) break
      }
      return buffer.array().copyOf(buffer.position())
    }
  }

  fun detectCharset(path: Path): Charset = detectCharset(readHead(path, DETECT_SIZE))

  private fun detectCharset(head: ByteArray): Charset {
    val bom = detectBomSize(head)
    if (bom == 3) return Charsets.UTF_8
    if (bom == 2) {
      if (head[0] == 0xFF.toByte()) return Charsets.UTF_16LE
      return Charsets.UTF_16BE
    }
    if (head.isEmpty()) return Charsets.UTF_8
    if (isDecodable(head, Charsets.UTF_8)) return Charsets.UTF_8
    val gbScore = score(head, gb18030, simplifiedMarkers)
    val big5Score = score(head, big5, traditionalMarkers)
    val chosen = if (big5Score > gbScore) big5 else gb18030
    logger.info { "Txt charset detection: gb18030=$gbScore big5=$big5Score chosen=$chosen" }
    return chosen
  }

  private fun detectBomSize(head: ByteArray): Int {
    if (head.size >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()) return 3
    if (head.size >= 2 && head[0] == 0xFF.toByte() && head[1] == 0xFE.toByte()) return 2
    if (head.size >= 2 && head[0] == 0xFE.toByte() && head[1] == 0xFF.toByte()) return 2
    return 0
  }

  private fun isUtf16(charset: Charset): Boolean = charset.name().uppercase(Locale.ROOT).startsWith("UTF-16")

  private fun newlineEndAt(
    chunk: ByteArray,
    index: Int,
    read: Int,
    unit: Int,
    littleEndian: Boolean,
  ): Int {
    if (unit == 1) return if (chunk[index] == LF) index + 1 else -1
    if (index + 1 >= read) return -1
    val first = chunk[index]
    val second = chunk[index + 1]
    return if (littleEndian) {
      if (first == LF && second == ZERO) index + 2 else -1
    } else {
      if (first == ZERO && second == LF) index + 2 else -1
    }
  }

  private fun score(
    bytes: ByteArray,
    charset: Charset,
    markers: Set<Char>,
  ): Int {
    val text = decodePadded(bytes, charset) ?: return Int.MIN_VALUE / 2
    var score = 0
    var cjk = 0
    var total = 0
    for (char in text) {
      total++
      when {
        char == REPLACEMENT -> score -= 20
        char in CJK_RANGE -> {
          cjk++
          score += 2
          if (char in markers) score += 6
        }

        char.isWhitespace() -> Unit
        char.code in 0x20..0x7E -> score += 1
        else -> score -= 1
      }
    }
    if (total == 0) return score
    score += (cjk * 100 / total)
    return score
  }

  private fun isDecodable(
    bytes: ByteArray,
    charset: Charset,
  ): Boolean {
    val decoder =
      charset
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    val length = completeUtf8Length(bytes)
    val input = ByteBuffer.wrap(bytes, 0, length)
    val output = CharBuffer.allocate(length + 16)
    val result = decoder.decode(input, output, true)
    if (result.isError) return false
    return !decoder.flush(output).isError
  }

  private fun completeUtf8Length(bytes: ByteArray): Int {
    var index = bytes.size - 1
    var back = 0
    while (index >= 0 && back < 3) {
      val byte = bytes[index].toInt() and 0xFF
      if (byte and 0xC0 == 0x80) {
        index--
        back++
      } else {
        val needed =
          when {
            byte and 0x80 == 0x00 -> 1
            byte and 0xE0 == 0xC0 -> 2
            byte and 0xF0 == 0xE0 -> 3
            byte and 0xF8 == 0xF0 -> 4
            else -> return index
          }
        return if (index + needed > bytes.size) index else bytes.size
      }
    }
    return bytes.size
  }

  private fun decodePadded(
    bytes: ByteArray,
    charset: Charset,
  ): String? {
    val decoder =
      charset
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    val input = ByteBuffer.wrap(bytes)
    val output = CharBuffer.allocate(bytes.size + 16)
    val result = decoder.decode(input, output, true)
    if (result.isError) return null
    decoder.flush(output)
    output.flip()
    return output.toString()
  }

  private fun readHead(
    path: Path,
    size: Int,
  ): ByteArray {
    openStream(path).use { input ->
      val buffer = ByteArray(size)
      var total = 0
      while (total < size) {
        val read = input.read(buffer, total, size - total)
        if (read < 0) break
        total += read
      }
      return buffer.copyOf(total)
    }
  }

  private fun openStream(path: Path): InputStream = path.inputStream()

  private fun isChapterTitle(line: String): Boolean {
    val trimmed = line.trim()
    if (trimmed.isEmpty() || trimmed.length > MAX_TITLE_LENGTH) return false
    if (trimmed.last() in rejectedLineEndings) return false
    return chapterPatterns.any { it.matches(trimmed) }
  }

  companion object {
    private const val CHUNK_SIZE = 64 * 1024
    private const val DETECT_SIZE = 128 * 1024
    private const val MAX_TITLE_LENGTH = 50
    private const val DEFAULT_TITLE = "正文"
    private val LF = '\n'.code.toByte()
    private val ZERO = 0.toByte()
    private const val REPLACEMENT = '\uFFFD'
    private val CJK_RANGE = '\u4E00'..'\u9FFF'

    private val simplifiedMarkers =
      "这说们为国来时会对过学发经头见动长样现将与进实点种声话问机给业间门东听气关并产师书华报马张难数车应亲务战电爱让认论义术结远资队带条变联权传红决达办运强区转众轻语基满识极黄脸钱党历议单据导飞观争编宝谈卫尽".toSet()

    private val traditionalMarkers =
      "這說們為國來時會對過學發經頭見動長樣現將與進實點種聲話問機給業間門東聽氣關並產師書華報馬張難數車應親務戰電愛讓認論義術結遠資隊帶條變聯權傳紅決達辦運強區轉眾輕語基滿識極黃臉錢黨歷議單據導飛觀爭編寶談衛盡".toSet()
  }
}
