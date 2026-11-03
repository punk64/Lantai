package org.lantai.infrastructure.online

import io.github.oshai.kotlinlogging.KotlinLogging
import org.lantai.domain.model.Author
import org.lantai.domain.model.Book
import org.lantai.domain.model.BookMetadata
import org.lantai.domain.model.BookMetadataAggregation
import org.lantai.domain.model.DomainEvent
import org.lantai.domain.model.Media
import org.lantai.domain.model.MediaType
import org.lantai.domain.model.OnlineChapter
import org.lantai.domain.model.OnlineManga
import org.lantai.domain.model.Series
import org.lantai.domain.model.SeriesMetadata
import org.lantai.domain.persistence.BookMetadataAggregationRepository
import org.lantai.domain.persistence.BookMetadataRepository
import org.lantai.domain.persistence.BookRepository
import org.lantai.domain.persistence.MediaRepository
import org.lantai.domain.persistence.OnlineChapterRepository
import org.lantai.domain.persistence.OnlineMangaRepository
import org.lantai.domain.persistence.SeriesMetadataRepository
import org.lantai.domain.persistence.SeriesRepository
import org.lantai.domain.service.SeriesLifecycle
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Path
import java.time.LocalDateTime
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries

private val logger = KotlinLogging.logger {}
private const val SOURCE_KIND = "suwayomi"

class OnlineException(
  message: String,
  cause: Throwable? = null,
) : RuntimeException(message, cause)

data class OnlineSubscribeResult(
  val seriesId: String,
  val title: String,
  val bookCount: Int,
  val alreadySubscribed: Boolean,
)

data class OnlineSyncResult(
  val seriesId: String,
  val added: Int,
  val updated: Int,
  val total: Int,
)

@Service
class OnlineSyncService(
  private val client: SuwayomiClient,
  private val onlineLibraryService: OnlineLibraryService,
  private val seriesRepository: SeriesRepository,
  private val seriesMetadataRepository: SeriesMetadataRepository,
  private val bookMetadataAggregationRepository: BookMetadataAggregationRepository,
  private val bookRepository: BookRepository,
  private val mediaRepository: MediaRepository,
  private val bookMetadataRepository: BookMetadataRepository,
  private val onlineMangaRepository: OnlineMangaRepository,
  private val onlineChapterRepository: OnlineChapterRepository,
  private val seriesLifecycle: SeriesLifecycle,
  private val onlineReadService: OnlineReadService,
  private val eventPublisher: ApplicationEventPublisher,
  private val transactionTemplate: TransactionTemplate,
) {
  fun sources(): List<SuwayomiSource> = client.sourceList()

  fun search(
    query: String,
    sourceId: String?,
    page: Int,
  ): SuwayomiBrowseResult {
    val targets = if (sourceId.isNullOrBlank()) client.sourceList().filter { it.id != "0" } else listOfNotNull(client.source(sourceId))
    if (targets.isEmpty()) return SuwayomiBrowseResult(emptyList(), false)
    val results = mutableListOf<SuwayomiManga>()
    var lastError: Exception? = null
    var hasNext = false
    targets.forEach { source ->
      try {
        val page1 = client.search(source.id, query, page)
        results += page1.mangaList.orEmpty()
        if (page1.hasNextPage == true) hasNext = true
      } catch (e: Exception) {
        logger.warn(e) { "Search failed on source ${source.name}" }
        lastError = e
      }
    }
    if (results.isEmpty() && lastError != null) throw OnlineException("Search failed: ${lastError?.message}", lastError)
    return SuwayomiBrowseResult(results.distinctBy { it.sourceId to it.id }, hasNext)
  }

  fun browse(
    sourceId: String,
    type: String,
    query: String?,
    page: Int,
    filters: List<SuwayomiFilterState> = emptyList(),
  ): SuwayomiBrowseResult {
    val kind = type.trim().uppercase()
    return when (kind) {
      "SEARCH" -> {
        if (query.isNullOrBlank()) SuwayomiBrowseResult(emptyList(), false) else client.fetchSourceManga(sourceId, "SEARCH", query.trim(), page, filters)
      }
      "LATEST" -> client.fetchSourceManga(sourceId, "LATEST", null, page, filters)
      else -> client.fetchSourceManga(sourceId, "POPULAR", null, page, filters)
    }
  }

  fun filters(sourceId: String): List<SuwayomiFilter> = client.sourceFilters(sourceId)

  fun manga(mangaId: Long): SuwayomiManga = client.manga(mangaId)

  fun preferences(sourceId: String): List<SuwayomiPreference> = client.sourcePreferences(sourceId)

  fun updatePreference(
    sourceId: String,
    position: Int,
    change: Map<String, Any>,
  ): List<SuwayomiPreference> {
    client.updateSourcePreference(sourceId, position, change)
    return client.sourcePreferences(sourceId)
  }

  fun subscribe(
    sourceId: String,
    mangaId: Long,
  ): OnlineSubscribeResult {
    onlineMangaRepository.findByRemoteOrNull(SOURCE_KIND, mangaId.toString())?.let {
      return OnlineSubscribeResult(it.seriesId, it.title, 0, true)
    }

    val library = onlineLibraryService.ensureLibrary()
    val source = client.source(sourceId) ?: throw OnlineException("Unknown source: $sourceId")
    val chapters = client.chapters(mangaId).sortedBy { it.index }
    if (chapters.isEmpty()) throw OnlineException("图源未返回任何章节，无法订阅")
    val manga = client.manga(mangaId)

    val seriesDir = onlineLibraryService.seriesDir(source.name, source.lang.orEmpty(), manga.title)

    val placeholders = chapters.map { onlineLibraryService.chapterPlaceholder(seriesDir, chapterTitle(it)) }

    val series =
      Series(
        name = manga.title,
        url = seriesDir.toUri().toURL(),
        fileLastModified = LocalDateTime.now(),
        libraryId = library.id,
        bookCount = chapters.size,
      )

    val books =
      chapters.mapIndexed { index, chapter ->
        Book(
          name = chapter.name,
          url = placeholders[index].toUri().toURL(),
          fileLastModified = LocalDateTime.now(),
          number = chapter.index,
          seriesId = series.id,
          libraryId = library.id,
        )
      }

    val onlineManga =
      OnlineManga(
        seriesId = series.id,
        remoteMangaId = mangaId.toString(),
        title = manga.title,
        coverUrl = manga.thumbnailUrl,
        status = manga.status,
        lastSyncAt = LocalDateTime.now(),
      )

    transactionTemplate.executeWithoutResult {
      seriesRepository.insert(series)
      seriesMetadataRepository.insert(
        SeriesMetadata(
          status = mapStatus(manga.status),
          title = manga.title,
          titleSort = manga.title,
          summary = manga.description.orEmpty(),
          readingDirection = SeriesMetadata.ReadingDirection.RIGHT_TO_LEFT,
          publisher = source.name,
          language = source.lang.orEmpty(),
          genres = manga.genre.orEmpty().toSet(),
          totalBookCount = chapters.size,
          seriesId = series.id,
        ),
      )
      bookMetadataAggregationRepository.insert(
        BookMetadataAggregation(
          authors = listOfNotNull(manga.author?.takeIf { it.isNotBlank() }?.let { Author(it, "author") }),
          seriesId = series.id,
        ),
      )
      bookRepository.insert(books)
      mediaRepository.insert(
        books.mapIndexed { index, book ->
          Media(
            status = Media.Status.READY,
            mediaType = MediaType.ZIP.type,
            pageCount = chapters[index].pageCount.coerceAtLeast(0),
            comment = "online",
            bookId = book.id,
          )
        },
      )
      bookMetadataRepository.insert(
        books.mapIndexed { index, book ->
          BookMetadata(
            title = chapters[index].name,
            number = bookNumber(chapters[index]),
            numberSort = chapters[index].index.toFloat(),
            bookId = book.id,
          )
        },
      )
      onlineMangaRepository.insert(onlineManga)
      onlineChapterRepository.insert(
        chapters.mapIndexed { index, chapter ->
          OnlineChapter(
            mangaId = onlineManga.id,
            bookId = books[index].id,
            idx = chapter.index,
            remoteId = chapter.id,
            remoteChapterIndex = chapter.index,
            title = chapter.name,
            pageCount = chapter.pageCount,
            downloaded = chapter.downloaded ?: false,
          )
        },
      )
      seriesRepository.update(series, false)
    }

    eventPublisher.publishEvent(DomainEvent.SeriesAdded(series))
    onlineReadService.ensureSeriesCover(series.id)
    logger.info { "Subscribed ${manga.title} with ${chapters.size} chapters as series ${series.id}" }

    return OnlineSubscribeResult(series.id, manga.title, chapters.size, false)
  }

  fun sync(seriesId: String): OnlineSyncResult {
    val onlineManga = onlineMangaRepository.findBySeriesIdOrNull(seriesId) ?: throw OnlineException("Not an online series: $seriesId")
    val series = seriesRepository.findByIdOrNull(seriesId) ?: throw OnlineException("Series not found: $seriesId")
    val remote = client.chapters(onlineManga.remoteMangaId.toLong()).sortedBy { it.index }
    val local = onlineChapterRepository.findAllByMangaId(onlineManga.id).associateBy { it.remoteChapterIndex }

    val added = remote.filter { it.index !in local }
    val refreshed =
      remote.mapNotNull { chapter ->
        val existing = local[chapter.index] ?: return@mapNotNull null
        val downloaded = chapter.downloaded ?: false
        if (existing.pageCount == chapter.pageCount && existing.downloaded == downloaded && existing.title == chapter.name) {
          null
        } else {
          existing.copy(title = chapter.name, pageCount = chapter.pageCount, downloaded = downloaded)
        }
      }

    if (added.isNotEmpty()) {
      val placeholders = added.map { onlineLibraryService.chapterPlaceholder(series.path, chapterTitle(it)) }
      val books =
        added.mapIndexed { index, chapter ->
          Book(
            name = chapter.name,
            url = placeholders[index].toUri().toURL(),
            fileLastModified = LocalDateTime.now(),
            number = chapter.index,
            seriesId = series.id,
            libraryId = series.libraryId,
          )
        }
      transactionTemplate.executeWithoutResult {
        bookRepository.insert(books)
        mediaRepository.insert(
          books.mapIndexed { index, book ->
            Media(
              status = Media.Status.READY,
              mediaType = MediaType.ZIP.type,
              pageCount = added[index].pageCount.coerceAtLeast(0),
              comment = "online",
              bookId = book.id,
            )
          },
        )
        bookMetadataRepository.insert(
          books.mapIndexed { index, book ->
            BookMetadata(
              title = added[index].name,
              number = bookNumber(added[index]),
              numberSort = added[index].index.toFloat(),
              bookId = book.id,
            )
          },
        )
        onlineChapterRepository.insert(
          added.mapIndexed { index, chapter ->
            OnlineChapter(
              mangaId = onlineManga.id,
              bookId = books[index].id,
              idx = chapter.index,
              remoteId = chapter.id,
              remoteChapterIndex = chapter.index,
              title = chapter.name,
              pageCount = chapter.pageCount,
              downloaded = chapter.downloaded ?: false,
            )
          },
        )
      }
    }

    if (refreshed.isNotEmpty()) onlineChapterRepository.update(refreshed)

    val total = onlineChapterRepository.findAllByMangaId(onlineManga.id).size
    seriesRepository.update(series.copy(bookCount = total), false)
    onlineMangaRepository.update(onlineManga.copy(lastSyncAt = LocalDateTime.now()))
    onlineReadService.ensureSeriesCover(seriesId)

    logger.info { "Synced ${series.name}: +$added ~${refreshed.size} total=$total" }
    return OnlineSyncResult(seriesId, added.size, refreshed.size, total)
  }

  fun unsubscribe(
    seriesId: String,
    purgeDownloads: Boolean = false,
  ) {
    val onlineManga = onlineMangaRepository.findBySeriesIdOrNull(seriesId) ?: throw OnlineException("Not an online series: $seriesId")
    val series = seriesRepository.findByIdOrNull(seriesId) ?: throw OnlineException("Series not found: $seriesId")
    val seriesPath = series.path

    onlineChapterRepository.deleteByMangaId(onlineManga.id)
    onlineMangaRepository.delete(onlineManga.id)
    seriesLifecycle.deleteMany(listOf(series))

    removePlaceholders(seriesPath, purgeDownloads)
    logger.info { "Unsubscribed $seriesId (purgeDownloads=$purgeDownloads)" }
  }

  fun listSubscriptions(): List<OnlineManga> = onlineMangaRepository.findAll().toList()

  private fun chapterTitle(chapter: SuwayomiChapter): String {
    val raw = "%04d-%s".format(chapter.index, chapter.name)
    val trimmed = raw.take(80).trimEnd('.', ' ')
    return if (trimmed.isBlank()) chapter.index.toString().padStart(4, '0') else trimmed
  }

  private fun bookNumber(chapter: SuwayomiChapter): String =
    chapter.chapterNumber
      ?.takeIf { it > 0 }
      ?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }
      ?: chapter.index.toString()

  private fun mapStatus(status: String?): SeriesMetadata.Status =
    when (status) {
      "COMPLETED", "PUBLISHING_FINISHED" -> SeriesMetadata.Status.ENDED
      "CANCELLED_ON_HIATUS", "ON_HIATUS" -> SeriesMetadata.Status.HIATUS
      else -> SeriesMetadata.Status.ONGOING
    }

  private fun removePlaceholders(
    seriesPath: Path,
    purgeDownloads: Boolean,
  ) {
    if (!seriesPath.exists()) return
    runCatching {
      seriesPath
        .listDirectoryEntries()
        .filter {
          val name = it.fileName.toString()
          name.endsWith(".placeholder") || (purgeDownloads && name.endsWith(".cbz", ignoreCase = true))
        }
        .forEach { it.deleteIfExists() }
      if (seriesPath.listDirectoryEntries().isEmpty()) seriesPath.deleteIfExists()
    }
  }
}
