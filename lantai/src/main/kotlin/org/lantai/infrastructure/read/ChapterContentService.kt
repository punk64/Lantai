package org.lantai.infrastructure.read

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import org.lantai.domain.model.Book
import org.lantai.domain.model.BookWithMedia
import org.lantai.domain.model.EpubTocEntry
import org.lantai.domain.model.Media
import org.lantai.domain.model.MediaExtensionEpub
import org.lantai.domain.model.MediaExtensionTxt
import org.lantai.domain.model.MediaFile
import org.lantai.domain.model.MediaProfile
import org.lantai.domain.model.ProxyExtension
import org.lantai.domain.model.SeriesMetadata
import org.lantai.domain.persistence.MediaRepository
import org.lantai.domain.service.BookAnalyzer
import org.lantai.infrastructure.mediacontainer.ContentDetector
import org.lantai.infrastructure.mediacontainer.txt.TxtExtractor
import org.lantai.infrastructure.online.OnlineReadService
import org.springframework.stereotype.Service
import java.net.URLDecoder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

data class ChapterContext(
  val refs: List<ChapterRefDto>,
  val positions: Map<String, Int>,
  val files: Map<String, MediaFile>,
  val toc: List<EpubTocEntry>,
  val titles: Map<String, String>,
  val pages: List<MediaFile> = emptyList(),
) {
  val total: Int get() = refs.size

  val indexByHref: Map<String, Int> get() = positions.entries.associate { (href, position) -> href to position + 1 }
}

@Service
class ChapterContentService(
  private val mediaRepository: MediaRepository,
  private val bookAnalyzer: BookAnalyzer,
  private val txtExtractor: TxtExtractor,
  private val contentDetector: ContentDetector,
  private val readImageService: ReadImageService,
  private val readProperties: ReadProperties,
  private val onlineReadService: OnlineReadService,
) {
  companion object {
    private const val SVG_PLACEHOLDER =
      "data:image/svg+xml;charset=utf-8,%3Csvg xmlns='http://www.w3.org/2000/svg' width='1' height='1'%3E%3C/svg%3E"
    private val xmlEncodingRegex = Regex("""encoding=["']([^"']+)["']""")
    private val imageTags = listOf("img", "image", "source", "video", "audio")
    private val SENTENCE_ENDS =
      setOf(
        '。', '！', '？', '…', '；', '：', '」', '』', '”', '’', '）', '】', '》', '〉',
        '.', '!', '?', ';', ':', ')', ']', '"', '\'',
      )
    private const val MAX_PARAGRAPH = 600
    private const val INDEX_CACHE_BOOKS = 4
    private const val ONLINE_MEDIA_COMMENT = "online"
  }

  private val headingCache = ConcurrentHashMap<String, String>()
  private val indexCache = ConcurrentHashMap<String, ChapterContext>()

  fun context(media: Media): ChapterContext {
    val key = media.bookId + "@" + media.lastModifiedDate
    indexCache[key]?.let { return it }
    val built = buildContext(media)
    indexCache.keys.removeIf { it.startsWith(media.bookId + "@") }
    if (indexCache.size >= INDEX_CACHE_BOOKS) indexCache.clear()
    indexCache[key] = built
    return built
  }

  fun clearIndex() {
    indexCache.clear()
    headingCache.clear()
  }

  private fun buildContext(media: Media): ChapterContext {
    val isEpub = media.profile == MediaProfile.EPUB
    val toc = if (isEpub) epubExtension(media)?.toc.orEmpty() else emptyList()
    val titles = titlesOf(toc)
    val pages = if (isEpub) epubPages(media) else emptyList()
    val txtChapters = if (media.profile == MediaProfile.TXT) txtExtension(media)?.chapters.orEmpty() else emptyList()
    val refs =
      when (media.profile) {
        MediaProfile.EPUB ->
          pages.mapIndexed { index, file ->
            ChapterRefDto(index + 1, titles[file.fileName] ?: prettyTitle(file.fileName, index + 1), file.fileSize?.toInt() ?: 0, file.fileName)
          }

        MediaProfile.TXT -> txtChapters.mapIndexed { index, chapter -> ChapterRefDto(index + 1, chapter.title, chapter.charCount.toInt(), chapter.href) }

        MediaProfile.DIVINA -> media.pages.mapIndexed { index, _ -> ChapterRefDto(index + 1, "第 ${index + 1} 页", 0, "#page=${index + 1}") }

        MediaProfile.PDF -> List(media.pageCount) { index -> ChapterRefDto(index + 1, "第 ${index + 1} 页", 0, "#page=${index + 1}") }

        null -> emptyList()
      }
    val positions =
      when (media.profile) {
        MediaProfile.EPUB -> pages.mapIndexed { index, file -> file.fileName to index }.toMap()
        MediaProfile.TXT -> txtChapters.mapIndexed { index, chapter -> chapter.href to index }.toMap()
        else -> emptyMap()
      }
    return ChapterContext(refs, positions, assetsOf(media), toc, titles, pages)
  }

  private fun assetsOf(media: Media): Map<String, MediaFile> =
    media.files.filter { it.subType != MediaFile.SubType.EPUB_PAGE }.associateBy { it.fileName }

  fun chapters(media: Media): List<ChapterRefDto> = context(media).refs

  fun outline(
    media: Media,
    ctx: ChapterContext = context(media),
  ): List<ReadOutlineEntryDto> {
    val byHref = ctx.indexByHref
    val charsByPosition = ctx.refs.associate { it.n to it.chars }

    fun entry(
      position: Int,
      title: String,
      depth: Int,
    ): ReadOutlineEntryDto = ReadOutlineEntryDto(position + 1, title, depth, charsByPosition[position + 1] ?: 0)

    when (media.profile) {
      MediaProfile.EPUB -> {
        val result = mutableListOf<ReadOutlineEntryDto>()

        fun firstPosition(entry: EpubTocEntry): Int? {
          entry.href?.substringBefore('#')?.let { byHref[it] }?.let { return it }
          entry.children.forEach { child -> firstPosition(child)?.let { return it } }
          return null
        }

        fun walk(
          entries: List<EpubTocEntry>,
          depth: Int,
        ) {
          entries.forEach { tocEntry ->
            val position = tocEntry.href?.substringBefore('#')?.let { byHref[it] }
            val anchor = position ?: if (tocEntry.children.isNotEmpty()) firstPosition(tocEntry) else null
            if (anchor != null) result += entry(anchor - 1, tocEntry.title, depth)
            if (tocEntry.children.isNotEmpty()) walk(tocEntry.children, depth + 1)
          }
        }
        walk(ctx.toc, 0)
        if (result.isNotEmpty()) return result
      }

      MediaProfile.TXT -> {
        val chapters = txtExtension(media)?.chapters.orEmpty()
        if (chapters.isNotEmpty()) return chapters.mapIndexed { index, chapter -> entry(index, chapter.title, 0) }
      }

      else -> Unit
    }
    return ctx.refs.mapIndexed { index, ref -> entry(index, ref.title, 0) }
  }

  fun bundle(
    book: Book,
    media: Media,
    number: Int,
    images: String,
    width: Int,
    format: String,
    contextPath: String,
    ctx: ChapterContext = context(media),
  ): ChapterBundleDto {
    val total = ctx.total
    if (number < 1 || number > total) throw IndexOutOfBoundsException("Chapter $number does not exist")
    val ref = ctx.refs[number - 1]
    val mode = images.lowercase().takeIf { it in setOf("inline", "url", "none") } ?: "inline"
    val prev = if (number > 1) number - 1 else null
    val next = if (number < total) number + 1 else null

    return when (media.profile) {
      MediaProfile.EPUB -> epubBundle(book, media, ctx, number, ref, total, mode, width, format, contextPath, prev, next)
      MediaProfile.TXT -> txtBundle(book, media, number, ref, total, prev, next)
      MediaProfile.DIVINA, MediaProfile.PDF -> pageBundle(book, media, number, ref, total, mode, width, format, contextPath, prev, next)
      null -> throw IllegalStateException("Book media is not ready")
    }
  }

  fun entryBytes(
    book: Book,
    media: Media,
    fileName: String,
  ): ByteArray = bookAnalyzer.getFileContent(BookWithMedia(book, media), fileName)

  fun pageHeading(
    book: Book,
    media: Media,
    fileName: String,
  ): String? {
    if (media.profile != MediaProfile.EPUB) return null
    val key = media.bookId + ":" + fileName
    headingCache[key]?.let { return it.ifEmpty { null } }
    val raw = runCatching { bookAnalyzer.getFileContent(BookWithMedia(book, media), fileName) }.getOrNull()
    val heading =
      raw?.let { bytes ->
        val doc = Jsoup.parse(String(bytes, charsetOf(bytes)), "", Parser.xmlParser())
        headingOf(doc)
      }.orEmpty()
    if (headingCache.size > 4096) headingCache.clear()
    headingCache[key] = heading
    return heading.ifEmpty { null }
  }

  private fun headingOf(doc: Document): String {
    val parts = mutableListOf<String>()
    for (block in doc.body().children()) {
      val text = block.text().replace('\u00A0', ' ').trim()
      if (text.isEmpty()) continue
      if (parts.size >= 2 || text.length > 40 || !isHeadingBlock(block)) break
      parts += text
    }
    val first = parts.firstOrNull().orEmpty()
    if (first.replace(" ", "").equals("目录", ignoreCase = true) || first.equals("contents", ignoreCase = true)) return "目录"
    return parts.joinToString(" ")
  }

  private fun isHeadingBlock(el: Element): Boolean {
    val tag = el.tagName().lowercase()
    if (tag.length == 2 && tag.startsWith("h") && tag[1].isDigit()) return true
    if (el.selectFirst("b, strong, [class*=bold], [style*=bold]") != null) return true
    val markers = el.attr("class") + " " + el.attr("style")
    return markers.contains("bold", ignoreCase = true)
  }

  fun pageBytes(
    book: Book,
    media: Media,
    page: Int,
  ): ByteArray {
    if (media.comment == ONLINE_MEDIA_COMMENT) {
      onlineReadService.pageOrNull(book.id, page)?.let { return it.bytes }
    }
    return bookAnalyzer.getPageContent(BookWithMedia(book, media), page)
  }

  private fun epubBundle(
    book: Book,
    media: Media,
    ctx: ChapterContext,
    number: Int,
    ref: ChapterRefDto,
    total: Int,
    mode: String,
    width: Int,
    format: String,
    contextPath: String,
    prev: Int?,
    next: Int?,
  ): ChapterBundleDto {
    val page = ctx.pages[number - 1]
    val raw = bookAnalyzer.getFileContent(BookWithMedia(book, media), page.fileName)
    val (html, images) = rewriteXhtml(book, media, ctx, page.fileName, raw, mode, width, format, contextPath)
    return ChapterBundleDto(
      chapter = number,
      title = ref.title,
      href = page.fileName,
      total = total,
      chars = ref.chars,
      mediaType = page.mediaType ?: "application/xhtml+xml",
      html = html,
      images = images,
      prev = prev,
      next = next,
    )
  }

  private fun txtBundle(
    book: Book,
    media: Media,
    number: Int,
    ref: ChapterRefDto,
    total: Int,
    prev: Int?,
    next: Int?,
  ): ChapterBundleDto {
    val extension = txtExtension(media) ?: throw IllegalStateException("Txt extension not found")
    val chapter = extension.chapters[number - 1]
    val bytes = txtExtractor.readChapterBytes(book.path, chapter)
    val charset = runCatching { Charset.forName(extension.encoding) }.getOrDefault(StandardCharsets.UTF_8)
    val text = String(bytes, charset)
    return ChapterBundleDto(
      chapter = number,
      title = ref.title,
      href = chapter.href,
      total = total,
      chars = text.length,
      mediaType = "text/html",
      html = plainTextToHtml(stripLeadingTitle(text, ref.title)),
      images = emptyList(),
      prev = prev,
      next = next,
    )
  }

  private fun pageBundle(
    book: Book,
    media: Media,
    number: Int,
    ref: ChapterRefDto,
    total: Int,
    mode: String,
    width: Int,
    format: String,
    contextPath: String,
    prev: Int?,
    next: Int?,
  ): ChapterBundleDto {
    val reference =
      when (media.profile) {
        MediaProfile.DIVINA -> readImageService.refOfEntry(media.pages[number - 1].fileName)
        else -> readImageService.refOfPage(number)
      }
    if (mode == "url" || mode == "none") {
      val src = if (mode == "none") SVG_PLACEHOLDER else imageUrl(book.id, reference, width, format, contextPath)
      val html = "<img src=\"$src\" alt=\"${escapeHtml(ref.title)}\" data-ref=\"$reference\">"
      val images =
        if (mode == "url") {
          val type = if (media.profile == MediaProfile.DIVINA) media.pages[number - 1].mediaType else "image/jpeg"
          listOf(ChapterImageDto(reference, 0L, type))
        } else {
          emptyList()
        }
      return ChapterBundleDto(
        chapter = number,
        title = ref.title,
        href = "#page=$number",
        total = total,
        chars = 0,
        mediaType = "text/html",
        html = html,
        images = images,
        prev = prev,
        next = next,
      )
    }
    val bytes = pageBytes(book, media, number)
    val rendered = readImageService.render(bytes, width, format)
    val src =
      when (mode) {
        "none" -> SVG_PLACEHOLDER
        "url" -> imageUrl(book.id, reference, width, format, contextPath)
        else -> readImageService.dataUri(rendered.bytes, rendered.mediaType)
      }
    val html = "<img src=\"$src\" alt=\"${escapeHtml(ref.title)}\" data-ref=\"$reference\">"
    val images =
      when (mode) {
        "inline" -> listOf(ChapterImageDto(reference, rendered.bytes.size.toLong(), rendered.mediaType))
        "url" -> listOf(ChapterImageDto(reference, 0L, rendered.mediaType))
        else -> emptyList()
      }
    return ChapterBundleDto(
      chapter = number,
      title = ref.title,
      href = "#page=$number",
      total = total,
      chars = 0,
      mediaType = "text/html",
      html = html,
      images = images,
      prev = prev,
      next = next,
    )
  }

  private fun rewriteXhtml(
    book: Book,
    media: Media,
    ctx: ChapterContext,
    fileName: String,
    raw: ByteArray,
    mode: String,
    width: Int,
    format: String,
    contextPath: String,
  ): Pair<String, List<ChapterImageDto>> {
    val doc = Jsoup.parse(String(raw, charsetOf(raw)), "", Parser.xmlParser())
    val baseDir = fileName.substringBeforeLast('/', "")
    val files = ctx.files
    val positions = ctx.indexByHref
    val collected = mutableListOf<ChapterImageDto>()

    doc.select("script, iframe, object, embed, noscript, base, meta[http-equiv]").remove()
    doc.allElements.forEach { element ->
      element.attributes().asList().map { it.key }.filter { it.lowercase().startsWith("on") }.forEach { element.removeAttr(it) }
    }

    doc.select("link").forEach { link ->
      val rel = link.attr("rel").lowercase()
      val href = link.attr("href")
      if (!rel.contains("stylesheet") || href.isBlank()) {
        link.remove()
        return@forEach
      }
      val target = resolvePath(baseDir, href)
      if (target == null) {
        link.remove()
        return@forEach
      }
      link.attr("href", resourceUrl(book.id, target, contextPath))
    }

    val cssUrlPattern = Regex("""url\(\s*(['"]?)([^'")]+)\1\s*\)""")
    fun rewriteCssUrls(css: String): String =
      cssUrlPattern.replace(css) { match ->
        val target = resolvePath(baseDir, match.groupValues[2])
        if (target == null) match.value else "url(\"${resourceUrl(book.id, target, contextPath)}\")"
      }
    doc.select("style").forEach { style ->
      val css = style.data().ifEmpty { style.html() }
      if (css.isNotEmpty() && css.contains("url(")) {
        style.empty()
        style.appendChild(org.jsoup.nodes.DataNode(rewriteCssUrls(css)))
      }
    }
    doc.select("[style]").forEach { element ->
      val inline = element.attr("style")
      if (inline.isNotEmpty() && inline.contains("url(")) element.attr("style", rewriteCssUrls(inline))
    }

    imageTags.forEach { tag ->
      doc.select(tag).forEach { element ->
        val attribute =
          when {
            element.hasAttr("src") -> "src"
            element.hasAttr("xlink:href") -> "xlink:href"
            element.tagName() == "image" && element.hasAttr("href") -> "href"
            else -> null
          } ?: return@forEach
        val target = resolvePath(baseDir, element.attr(attribute)) ?: return@forEach
        val file = files[target]
        if (file == null) {
          element.attr(attribute, SVG_PLACEHOLDER)
          return@forEach
        }
        val mediaType = file.mediaType.orEmpty()
        if (!contentDetector.isImage(mediaType)) {
          element.attr(attribute, resourceUrl(book.id, target, contextPath))
          return@forEach
        }
        val reference = readImageService.refOfEntry(target)
        element.attr("data-ref", reference)
        when (mode) {
          "none" -> element.attr(attribute, SVG_PLACEHOLDER)
          "url" -> {
            element.attr(attribute, imageUrl(book.id, reference, width, format, contextPath))
            collected += ChapterImageDto(reference, 0L, mediaType)
          }
          else -> {
            val bytes = runCatching { bookAnalyzer.getFileContent(BookWithMedia(book, media), target) }.getOrNull()
            if (bytes == null) {
              element.attr(attribute, SVG_PLACEHOLDER)
              return@forEach
            }
            val rendered = readImageService.render(bytes, width, format)
            element.attr(attribute, readImageService.dataUri(rendered.bytes, rendered.mediaType))
            if (collected.size < readProperties.inlineMaxImages) {
              collected += ChapterImageDto(reference, rendered.bytes.size.toLong(), rendered.mediaType)
            }
          }
        }
      }
    }

    doc.select("a[href]").forEach { anchor ->
      val href = anchor.attr("href")
      if (href.startsWith("#") || href.isBlank()) return@forEach
      val fragment = href.substringAfter('#', "")
      val target = resolvePath(baseDir, href) ?: return@forEach
      val chapter = positions[target] ?: return@forEach
      anchor.attr("href", "#ch-$chapter")
      anchor.attr("data-ch", chapter.toString())
      if (fragment.isNotEmpty()) anchor.attr("data-frag", fragment)
    }

    return doc.outerHtml() to collected
  }

  private fun epubPages(media: Media): List<MediaFile> = media.files.filter { it.subType == MediaFile.SubType.EPUB_PAGE }

  private fun epubExtension(media: Media): MediaExtensionEpub? =
    when (val extension = media.extension) {
      is ProxyExtension if extension.proxyForType<MediaExtensionEpub>() -> mediaRepository.findExtensionByIdOrNull(media.bookId) as? MediaExtensionEpub
      is MediaExtensionEpub -> extension
      else -> null
    }

  private fun txtExtension(media: Media): MediaExtensionTxt? =
    when (val extension = media.extension) {
      is ProxyExtension if extension.proxyForType<MediaExtensionTxt>() -> mediaRepository.findExtensionByIdOrNull(media.bookId) as? MediaExtensionTxt
      is MediaExtensionTxt -> extension
      else -> null
    }

  private fun titlesOf(toc: List<EpubTocEntry>): Map<String, String> {
    val result = mutableMapOf<String, String>()
    fun walk(entries: List<EpubTocEntry>) {
      entries.forEach { entry ->
        val href = entry.href?.substringBefore('#')?.takeIf { it.isNotBlank() }
        if (href != null) result.putIfAbsent(href, entry.title)
        if (entry.children.isNotEmpty()) walk(entry.children)
      }
    }
    walk(toc)
    return result
  }

  private fun resolvePath(
    baseDir: String,
    href: String,
  ): String? {
    val clean = href.substringBefore('#').substringBefore('?').trim()
    if (clean.isEmpty()) return null
    if (clean.startsWith("data:") || clean.startsWith("//")) return null
    val decoded =
      try {
        URLDecoder.decode(clean, StandardCharsets.UTF_8)
      } catch (_: IllegalArgumentException) {
        clean
      }
    if (decoded.startsWith("data:") || decoded.startsWith("http:") || decoded.startsWith("https:")) return null
    val segments = if (decoded.startsWith("/")) mutableListOf() else baseDir.split('/').filter { it.isNotEmpty() }.toMutableList()
    decoded.split('/').forEach { segment ->
      when (segment) {
        "", "." -> Unit
        ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.size - 1)
        else -> segments += segment
      }
    }
    return segments.joinToString("/").ifEmpty { null }
  }

  private fun charsetOf(bytes: ByteArray): Charset {
    if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) return StandardCharsets.UTF_8
    if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return StandardCharsets.UTF_16LE
    if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return StandardCharsets.UTF_16BE
    val head = String(bytes, 0, min(bytes.size, 256), StandardCharsets.ISO_8859_1)
    xmlEncodingRegex.find(head)?.groupValues?.get(1)?.let { name ->
      runCatching { Charset.forName(name) }.getOrNull()?.let { return it }
    }
    return StandardCharsets.UTF_8
  }

  private fun plainTextToHtml(text: String): String {
    val paragraphs = mutableListOf<String>()
    var current: StringBuilder? = null
    text
      .replace("\r\n", "\n")
      .replace('\r', '\n')
      .split('\n')
      .forEach { raw ->
        val line = raw.trimEnd()
        val trimmed = line.trim()
        if (trimmed.isEmpty()) {
          current?.let { paragraphs.add(it.toString()) }
          current = null
          return@forEach
        }
        val indented = line.startsWith("　") || line.startsWith("\t") || line.startsWith("  ")
        val open = current
        if (open == null || indented || endsSentence(open)) {
          open?.let { paragraphs.add(it.toString()) }
          current = StringBuilder(trimmed)
        } else {
          open.append(trimmed)
        }
      }
    current?.let { paragraphs.add(it.toString()) }
    return paragraphs
      .flatMap { splitLongParagraph(it.trim()) }
      .filter { it.isNotEmpty() }
      .joinToString("") { "<p>${escapeHtml(it)}</p>" }
  }

  private fun splitLongParagraph(text: String): List<String> {
    if (text.length <= MAX_PARAGRAPH) return listOf(text)
    val parts = mutableListOf<String>()
    var start = 0
    var lastBreak = -1
    for (index in text.indices) {
      if (text[index] in SENTENCE_ENDS) lastBreak = index
      if (index - start + 1 >= MAX_PARAGRAPH && lastBreak >= start) {
        parts.add(text.substring(start, lastBreak + 1))
        start = lastBreak + 1
        lastBreak = -1
      }
    }
    if (start < text.length) parts.add(text.substring(start))
    return parts
  }

  private fun endsSentence(builder: StringBuilder): Boolean {
    val last = builder.trimEnd().lastOrNull() ?: return true
    return last in SENTENCE_ENDS
  }

  private fun stripLeadingTitle(
    text: String,
    title: String,
  ): String {
    val expected = title.trim()
    if (expected.isEmpty()) return text
    val index = text.indexOf('\n')
    val head = (if (index >= 0) text.substring(0, index) else text).trim()
    if (head != expected) return text
    return if (index >= 0) text.substring(index + 1) else ""
  }

  private fun escapeHtml(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

  private fun prettify(fileName: String): String = fileName.substringAfterLast('/').substringBeforeLast('.')

  private fun prettyTitle(
    fileName: String,
    number: Int,
  ): String {
    val base = prettify(fileName)
    return if (base.matches(Regex("""(?i)(index[_-]?split|split|part|section|chapter|ch|text|page|p)[_-]?\d+"""))) "第 $number 节" else base
  }

  private fun imageUrl(
    bookId: String,
    reference: String,
    width: Int,
    format: String,
    contextPath: String,
  ): String = "$contextPath/api/lantai/v1/read/$bookId/img/$reference?w=$width&fmt=$format"

  private fun resourceUrl(
    bookId: String,
    fileName: String,
    contextPath: String,
  ): String =
    "$contextPath/api/v1/books/$bookId/resource/" +
      fileName.split('/').joinToString("/") { java.net.URLEncoder.encode(it, StandardCharsets.UTF_8).replace("+", "%20") }
}
