package org.lantai.infrastructure.read

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.lantai.domain.model.Book
import org.lantai.domain.model.Media
import org.lantai.domain.model.MediaExtensionTxt
import org.lantai.domain.model.MediaFile
import org.lantai.domain.model.MediaProfile
import org.lantai.domain.model.ProxyExtension
import org.lantai.domain.model.readablePath
import org.lantai.domain.persistence.MediaRepository
import org.lantai.infrastructure.mediacontainer.txt.TxtExtractor
import org.springframework.stereotype.Service
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.stream.IntStream
import java.util.zip.ZipFile

data class BookSearchHitDto(
  val chapter: Int,
  val title: String,
  val snippet: String,
  val offset: Int,
)

data class BookSearchResultDto(
  val query: String,
  val total: Int,
  val chapters: Int,
  val matchedChapters: Int,
  val scanned: Boolean,
  val hits: List<BookSearchHitDto>,
)

@Service
class BookSearchService(
  private val mediaRepository: MediaRepository,
  private val txtExtractor: TxtExtractor,
  private val chapterContentService: ChapterContentService,
) {
  companion object {
    private const val SNIPPET_PAD = 34
    private const val MAX_CACHED_BOOKS = 3
    private val encodingRegex = Regex("""encoding=["']([^"']+)["']""")
    private val whitespaceRegex = Regex("\\s+")
  }

  private class Indexed(val pages: List<String>)

  private val cache = ConcurrentHashMap<String, Indexed>()
  private val building = ConcurrentHashMap<String, Any>()

  fun search(
    book: Book,
    media: Media,
    query: String,
    limit: Int,
  ): BookSearchResultDto {
    val q = query.trim()
    if (q.isEmpty()) return BookSearchResultDto(q, 0, 0, 0, false, emptyList())
    val ctx = chapterContentService.context(media)
    val pages = pagesOf(book, media)
    if (pages.isEmpty()) return BookSearchResultDto(q, 0, ctx.total, 0, false, emptyList())

    val cap = limit.coerceIn(1, 100)
    val hits = mutableListOf<BookSearchHitDto>()
    var total = 0
    var matchedChapters = 0

    for (index in pages.indices) {
      val text = pages[index]
      if (text.isEmpty()) continue
      var from = 0
      var chapterHits = 0
      while (from < text.length) {
        val at = text.indexOf(q, from, ignoreCase = true)
        if (at < 0) break
        total += 1
        chapterHits += 1
        if (hits.size < cap) {
          val start = (at - SNIPPET_PAD).coerceAtLeast(0)
          val end = (at + q.length + SNIPPET_PAD).coerceAtMost(text.length)
          hits +=
            BookSearchHitDto(
              chapter = index + 1,
              title = ctx.refs.getOrNull(index)?.title ?: "第 ${index + 1} 章",
              snippet = text.substring(start, end).replace(whitespaceRegex, " ").trim(),
              offset = at,
            )
        }
        from = at + q.length
      }
      if (chapterHits > 0) matchedChapters += 1
    }

    return BookSearchResultDto(q, total, ctx.total, matchedChapters, true, hits)
  }

  private fun pagesOf(
    book: Book,
    media: Media,
  ): List<String> {
    cache[book.id]?.let { return it.pages }
    val pages =
      when (media.profile) {
        MediaProfile.EPUB -> epubPages(book, media)
        MediaProfile.TXT -> txtPages(book, media)
        else -> emptyList()
      }
    if (pages.isNotEmpty()) {
      if (cache.size >= MAX_CACHED_BOOKS) cache.clear()
      cache[book.id] = Indexed(pages)
    }
    return pages
  }

  private fun epubPages(
    book: Book,
    media: Media,
  ): List<String> {
    val files = media.files.filter { it.subType == MediaFile.SubType.EPUB_PAGE }
    if (files.isEmpty()) return emptyList()
    val raw = HashMap<String, ByteArray>(files.size * 2)
    runCatching {
      ZipFile(book.readablePath().toFile()).use { zip ->
        files.forEach { file ->
          val entry = zip.getEntry(file.fileName) ?: return@forEach
          raw[file.fileName] = zip.getInputStream(entry).use { it.readBytes() }
        }
      }
    }
    val texts = arrayOfNulls<String>(files.size)
    IntStream.range(0, files.size).parallel().forEach { index ->
      val bytes = raw[files[index].fileName] ?: return@forEach
      texts[index] = htmlToText(bytes)
    }
    return texts.map { it ?: "" }
  }

  private fun htmlToText(bytes: ByteArray): String {
    val doc =
      runCatching { Jsoup.parse(decode(bytes), "", Parser.xmlParser()) }.getOrNull()
        ?: return ""
    doc.select("script,style,nav,svg,head,title").remove()
    return doc.text().replace('\u00A0', ' ')
  }

  private fun txtPages(
    book: Book,
    media: Media,
  ): List<String> {
    val extension = txtExtension(media) ?: return emptyList()
    val charset = runCatching { Charset.forName(extension.encoding) }.getOrDefault(StandardCharsets.UTF_8)
    return extension.chapters.map { chapter ->
      runCatching { String(txtExtractor.readChapterBytes(book.path, chapter), charset) }.getOrDefault("")
    }
  }

  private fun txtExtension(media: Media): MediaExtensionTxt? =
    when (val extension = media.extension) {
      is ProxyExtension if extension.proxyForType<MediaExtensionTxt>() -> mediaRepository.findExtensionByIdOrNull(media.bookId) as? MediaExtensionTxt
      is MediaExtensionTxt -> extension
      else -> null
    }

  private fun decode(bytes: ByteArray): String {
    val head = String(bytes, 0, minOf(bytes.size, 240), StandardCharsets.ISO_8859_1)
    val name = encodingRegex.find(head)?.groupValues?.get(1)
    val charset = runCatching { Charset.forName(name) }.getOrDefault(StandardCharsets.UTF_8)
    return String(bytes, charset)
  }

  fun invalidate(bookId: String) {
    cache.remove(bookId)
  }
}
