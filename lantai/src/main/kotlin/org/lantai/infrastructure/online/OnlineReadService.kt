package org.lantai.infrastructure.online

import io.github.oshai.kotlinlogging.KotlinLogging
import org.lantai.domain.model.BookPage
import org.lantai.domain.model.Dimension
import org.lantai.domain.model.MarkSelectedPreference
import org.lantai.domain.model.OnlineChapter
import org.lantai.domain.model.OnlineContentKinds
import org.lantai.domain.model.ThumbnailBook
import org.lantai.domain.model.ThumbnailSeries
import org.lantai.domain.persistence.MediaRepository
import org.lantai.domain.persistence.OnlineChapterRepository
import org.lantai.domain.persistence.OnlineMangaRepository
import org.lantai.domain.persistence.SeriesRepository
import org.lantai.domain.persistence.ThumbnailBookRepository
import org.lantai.domain.persistence.ThumbnailSeriesRepository
import org.lantai.domain.service.BookLifecycle
import org.lantai.domain.service.SeriesLifecycle
import org.springframework.stereotype.Service
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.imageio.ImageIO
import kotlin.io.path.exists

private val logger = KotlinLogging.logger {}
private const val PAGE_MEDIA_TYPE = "image/jpeg"
private const val THUMB_RETRY_GAP_MS = 600_000L

class OnlinePage(
  val bytes: ByteArray,
  val mediaType: String,
)

@Service
class OnlineReadService(
  private val onlineChapterRepository: OnlineChapterRepository,
  private val onlineMangaRepository: OnlineMangaRepository,
  private val mediaRepository: MediaRepository,
  private val thumbnailBookRepository: ThumbnailBookRepository,
  private val thumbnailSeriesRepository: ThumbnailSeriesRepository,
  private val seriesRepository: SeriesRepository,
  private val seriesLifecycle: SeriesLifecycle,
  private val bookLifecycle: BookLifecycle,
  private val onlineLibraryService: OnlineLibraryService,
  private val clients: OnlineRemoteClients,
) {
  private val locks = ConcurrentHashMap<String, Any>()
  private val thumbFailAt = ConcurrentHashMap<String, Long>()
  private val coverFailAt = ConcurrentHashMap<String, Long>()
  private val coverWarmer =
    Executors.newSingleThreadExecutor { runnable ->
      Thread(runnable, "online-cover-warmer").apply { isDaemon = true }
    }

  fun pageOrNull(
    bookId: String,
    pageNumber: Int,
  ): OnlinePage? {    if (pageNumber < 1) return null
    val chapter = onlineChapterRepository.findByBookIdOrNull(bookId) ?: return null
    if (chapter.downloaded || chapter.remoteId <= 0) return null
    if (chapter.pageCount > 0 && pageNumber > chapter.pageCount) throw IllegalArgumentException("Page number does not exist")
    val manga = onlineMangaRepository.findByIdOrNull(chapter.mangaId) ?: return null
    val page = clients.of(manga.sourceKind).page(manga.remoteMangaId.toLong(), chapter.remoteChapterIndex, pageNumber - 1) ?: return null
    return OnlinePage(page.bytes, page.mediaType)
  }

  fun contentOrNull(
    bookId: String,
    chapterNo: Int = 1,
  ): RemoteChapterContent? {
    val chapter = chapterAt(bookId, chapterNo) ?: return null
    val manga = onlineMangaRepository.findByIdOrNull(chapter.mangaId) ?: return null
    if (!OnlineContentKinds.isTextual(OnlineContentKinds.resolve(chapter.contentKind, manga.contentKind))) return null
    return runCatching { clients.of(manga.sourceKind).chapterContent(chapter.remoteId) }.getOrNull()
  }

  fun assetOrNull(
    bookId: String,
    chapterNo: Int,
    assetId: String,
  ): OnlinePage? {
    val chapter = chapterAt(bookId, chapterNo) ?: return null
    val manga = onlineMangaRepository.findByIdOrNull(chapter.mangaId) ?: return null
    if (!OnlineContentKinds.isTextual(OnlineContentKinds.resolve(chapter.contentKind, manga.contentKind))) return null
    val remote = runCatching { clients.of(manga.sourceKind).asset(chapter.remoteId, assetId) }.getOrNull() ?: return null
    return OnlinePage(remote.bytes, remote.mediaType)
  }

  private fun chapterAt(
    bookId: String,
    chapterNo: Int,
  ): OnlineChapter? {
    val chapters = onlineChapterRepository.findAllByBookId(bookId)
    if (chapters.isEmpty()) return null
    return chapters.sortedBy { it.idx }.getOrNull(chapterNo - 1)
  }

  fun ensureBookThumbnail(bookId: String) {
    val chapter = onlineChapterRepository.findByBookIdOrNull(bookId) ?: return
    if (chapter.downloaded) return
    if (hasThumbnail(bookId)) return
    if (System.currentTimeMillis() - (thumbFailAt[bookId] ?: 0L) < THUMB_RETRY_GAP_MS) return
    synchronized(locks.computeIfAbsent("thumbnail:$bookId") { Any() }) {
      if (hasThumbnail(bookId)) return
      runCatching {
        val manga = onlineMangaRepository.findByIdOrNull(chapter.mangaId) ?: error("manga ${chapter.mangaId} not found")
        val textual = OnlineContentKinds.isTextual(OnlineContentKinds.resolve(chapter.contentKind, manga.contentKind))
        val remote: RemotePage? =
          if (textual) {
            clients.of(manga.sourceKind).thumbnail(manga.remoteMangaId.toLong())?.let { RemotePage(it, PAGE_MEDIA_TYPE) }
          } else {
            clients.of(manga.sourceKind).page(manga.remoteMangaId.toLong(), chapter.remoteChapterIndex, 0)
          }
        val payload = remote ?: error("no remote thumbnail for manga ${manga.remoteMangaId}")
        val image = ImageIO.read(ByteArrayInputStream(payload.bytes)) ?: error("undecodable thumbnail for book $bookId")
        val file = onlineLibraryService.thumbnailDir().resolve("$bookId.jpg")
        Files.write(file, payload.bytes)
        bookLifecycle.addThumbnailForBook(
          ThumbnailBook(
            url = file.toUri().toURL(),
            type = ThumbnailBook.Type.SIDECAR,
            mediaType = payload.mediaType,
            fileSize = Files.size(file),
            dimension = Dimension(image.width, image.height),
            bookId = bookId,
          ),
          MarkSelectedPreference.YES,
        )
        thumbFailAt.remove(bookId)
      }.onFailure {
        thumbFailAt[bookId] = System.currentTimeMillis()
        logger.warn(it) { "Book thumbnail generation failed for online book $bookId" }
      }
    }
  }

  private fun hasThumbnail(bookId: String): Boolean = thumbnailBookRepository.findSelectedByBookIdOrNull(bookId)?.exists() == true

  fun ensurePages(bookId: String) {
    if (onlineChapterRepository.findByBookIdOrNull(bookId) == null) return
    synchronized(locks.computeIfAbsent(bookId) { Any() }) {
      runCatching { applyPages(bookId) }.onFailure {
        logger.warn(it) { "Lazy page load failed for book $bookId" }
      }
    }
  }

  fun ensureSeriesCover(seriesId: String) {
    if (hasSeriesThumbnail(seriesId)) return
    if (System.currentTimeMillis() - (coverFailAt[seriesId] ?: 0L) < THUMB_RETRY_GAP_MS) return
    val onlineManga = onlineMangaRepository.findBySeriesIdOrNull(seriesId) ?: return
    val series = seriesRepository.findByIdOrNull(seriesId) ?: return
    synchronized(locks.computeIfAbsent("cover:$seriesId") { Any() }) {
      if (hasSeriesThumbnail(seriesId)) return
      runCatching {
        val cover = series.path.resolve("cover.jpg")
        if (!cover.exists()) {
          val bytes = clients.of(onlineManga.sourceKind).thumbnail(onlineManga.remoteMangaId.toLong())
          if (bytes == null || bytes.isEmpty()) error("no remote cover for manga ${onlineManga.remoteMangaId}")
          Files.createDirectories(series.path)
          Files.write(cover, bytes)
        }

        val image = ImageIO.read(cover.toFile()) ?: error("undecodable cover for series $seriesId")

        seriesLifecycle.addThumbnailForSeries(
          ThumbnailSeries(
            url = cover.toUri().toURL(),
            type = ThumbnailSeries.Type.SIDECAR,
            mediaType = PAGE_MEDIA_TYPE,
            fileSize = Files.size(cover),
            dimension = Dimension(image.width, image.height),
            seriesId = seriesId,
          ),
          MarkSelectedPreference.YES,
        )
        coverFailAt.remove(seriesId)
      }.onFailure {
        coverFailAt[seriesId] = System.currentTimeMillis()
        logger.warn(it) { "Series cover generation failed for online series $seriesId" }
      }
    }
  }

  fun ensureBookThumbnails(bookIds: Collection<String>, limit: Int = 200) {
    if (bookIds.isEmpty()) return
    val targets = bookIds.take(limit)
    coverWarmer.execute { targets.forEach { runCatching { ensureBookThumbnail(it) } } }
  }

  private fun hasSeriesThumbnail(seriesId: String): Boolean =
    thumbnailSeriesRepository.findSelectedBySeriesIdOrNull(seriesId)?.exists() == true

  private fun applyPages(bookId: String) {
    val chapter = onlineChapterRepository.findByBookIdOrNull(bookId) ?: return
    if (chapter.downloaded) return
    val media = mediaRepository.findByIdOrNull(bookId) ?: return
    if (media.comment != OnlineContentKinds.MEDIA_COMMENT_IMAGE) return
    if (media.pages.isNotEmpty()) return

    val pageCount =
      if (chapter.pageCount > 0) {
        chapter.pageCount
      } else {
        if (chapter.remoteId <= 0) {
          logger.warn { "Online chapter of book $bookId has no remote id, cannot resolve page count" }
          return
        }
        val manga = onlineMangaRepository.findByIdOrNull(chapter.mangaId) ?: return
        clients.of(manga.sourceKind).chapterPages(chapter.remoteId)
      }

    if (pageCount <= 0) {
      logger.warn { "Remote source reported page count $pageCount for chapter ${chapter.remoteId}" }
      return
    }

    val pages = (1..pageCount).map { BookPage(fileName = "%03d.jpg".format(it), mediaType = PAGE_MEDIA_TYPE) }

    mediaRepository.update(media.copy(pages = pages, pageCount = pageCount))
    if (chapter.pageCount != pageCount) onlineChapterRepository.update(chapter.copy(pageCount = pageCount))

    logger.info { "Lazy loaded $pageCount pages for online book $bookId" }
  }
}
