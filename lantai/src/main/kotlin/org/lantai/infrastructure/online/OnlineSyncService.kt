package org.lantai.infrastructure.online

import io.github.oshai.kotlinlogging.KotlinLogging
import org.lantai.application.tasks.HIGHEST_PRIORITY
import org.lantai.application.tasks.TaskEmitter
import org.lantai.domain.model.Author
import org.lantai.domain.model.Book
import org.lantai.domain.model.BookMetadata
import org.lantai.domain.model.BookMetadataAggregation
import org.lantai.domain.model.DomainEvent
import org.lantai.domain.model.Library
import org.lantai.domain.model.Media
import org.lantai.domain.model.MediaExtensionTxt
import org.lantai.domain.model.MediaType
import org.lantai.domain.model.OnlineChapter
import org.lantai.domain.model.OnlineContentKinds
import org.lantai.domain.model.OnlineManga
import org.lantai.domain.model.Series
import org.lantai.domain.model.SeriesMetadata
import org.lantai.domain.model.TxtChapter
import org.lantai.domain.persistence.BookMetadataAggregationRepository
import org.lantai.domain.persistence.BookMetadataRepository
import org.lantai.domain.persistence.BookRepository
import org.lantai.domain.persistence.LibraryRepository
import org.lantai.domain.persistence.MediaRepository
import org.lantai.domain.persistence.OnlineCacheJobRepository
import org.lantai.domain.persistence.OnlineChapterRepository
import org.lantai.domain.persistence.OnlineMangaRepository
import org.lantai.domain.persistence.SeriesMetadataRepository
import org.lantai.domain.persistence.SeriesRepository
import org.lantai.domain.service.LibraryLifecycle
import org.lantai.domain.service.SeriesLifecycle
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

private val logger = KotlinLogging.logger {}

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
  private val clients: OnlineRemoteClients,
  private val onlineLibraryService: OnlineLibraryService,
  private val seriesRepository: SeriesRepository,
  private val seriesMetadataRepository: SeriesMetadataRepository,
  private val bookMetadataAggregationRepository: BookMetadataAggregationRepository,
  private val bookRepository: BookRepository,
  private val mediaRepository: MediaRepository,
  private val bookMetadataRepository: BookMetadataRepository,
  private val onlineMangaRepository: OnlineMangaRepository,
  private val onlineChapterRepository: OnlineChapterRepository,
  private val onlineCacheJobRepository: OnlineCacheJobRepository,
  private val seriesLifecycle: SeriesLifecycle,
  private val libraryLifecycle: LibraryLifecycle,
  private val libraryRepository: LibraryRepository,
  private val taskEmitter: TaskEmitter,
  private val onlineReadService: OnlineReadService,
  private val eventPublisher: ApplicationEventPublisher,
  private val transactionTemplate: TransactionTemplate,
) {
  fun sources(kind: String? = null): List<RemoteSource> = clients.of(kind).sources()

  fun search(
    query: String,
    sourceId: String?,
    page: Int,
  ): RemoteBrowseResult {
    val client = clients.ofSource(sourceId)
    val targets = if (sourceId.isNullOrBlank()) client.sources().filter { it.id != "0" } else listOfNotNull(client.source(sourceId))
    if (targets.isEmpty()) return RemoteBrowseResult(emptyList(), false)
    val results = mutableListOf<RemoteManga>()
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
    return RemoteBrowseResult(results.distinctBy { it.sourceId to it.id }, hasNext)
  }

  fun browse(
    sourceId: String,
    type: String,
    query: String?,
    page: Int,
    filters: List<OnlineFilterState> = emptyList(),
  ): RemoteBrowseResult {
    val kind = type.trim().uppercase()
    val client = clients.ofSource(sourceId)
    return when (kind) {
      "SEARCH" -> {
        if (query.isNullOrBlank()) RemoteBrowseResult(emptyList(), false) else client.browse(sourceId, kind, query.trim(), page, filters)
      }
      "LATEST" -> client.browse(sourceId, kind, null, page, filters)
      else -> client.browse(sourceId, "POPULAR", null, page, filters)
    }
  }

  fun filters(sourceId: String): List<OnlineFilter> = clients.ofSource(sourceId).filters(sourceId)

  fun manga(
    mangaId: Long,
    sourceId: String? = null,
  ): RemoteManga = clients.ofSource(sourceId).manga(mangaId)

  fun subscribe(
    sourceId: String,
    mangaId: Long,
  ): OnlineSubscribeResult {
    val kind = OnlineSourceIds.kindOf(sourceId)
    val client = clients.of(kind)
    onlineMangaRepository.findByRemoteOrNull(kind, mangaId.toString())?.let {
      return OnlineSubscribeResult(it.seriesId, it.title, 0, true)
    }

    val library = onlineLibraryService.ensureLibrary()
    val source = client.source(sourceId) ?: throw OnlineException("Unknown source: $sourceId")
    val contentKind = OnlineContentKinds.normalize(source.contentType)
    val contentFormat = OnlineContentKinds.normalizeFormat(source.contentFormat)
    val chapters = client.chapters(mangaId).sortedBy { it.index }
    if (chapters.isEmpty()) throw OnlineException("源未返回任何章节，无法加入书架")
    val manga = client.manga(mangaId)

    val seriesDir = onlineLibraryService.seriesDir(source.name, source.lang.orEmpty(), manga.title)

    val textual = contentKind == OnlineContentKinds.TEXT

    val series =
      Series(
        name = manga.title,
        url = seriesDir.toUri().toURL(),
        fileLastModified = LocalDateTime.now(),
        libraryId = library.id,
        bookCount = chapters.size,
      )

    val onlineManga =
      OnlineManga(
        seriesId = series.id,
        remoteMangaId = mangaId.toString(),
        title = manga.title,
        sourceKind = kind,
        contentKind = contentKind,
        contentFormat = contentFormat,
        coverUrl = manga.thumbnailUrl,
        status = manga.status,
        lastSyncAt = LocalDateTime.now(),
      )

    if (textual) {
      val placeholder = onlineLibraryService.chapterPlaceholder(seriesDir, manga.title)
      val book =
        Book(
          name = manga.title,
          url = placeholder.toUri().toURL(),
          fileLastModified = LocalDateTime.now(),
          number = 1,
          seriesId = series.id,
          libraryId = library.id,
        )

      transactionTemplate.executeWithoutResult {
        seriesRepository.insert(series)
        seriesMetadataRepository.insert(seriesMetadataFor(manga, source, contentKind, chapters.size, series.id))
        bookMetadataAggregationRepository.insert(
          BookMetadataAggregation(
            authors = listOfNotNull(manga.author?.takeIf { it.isNotBlank() }?.let { Author(it, "author") }),
            seriesId = series.id,
          ),
        )
        bookRepository.insert(listOf(book))
        mediaRepository.insert(listOf(textMediaFor(book.id, chapters)))
        bookMetadataRepository.insert(
          listOf(BookMetadata(title = manga.title, number = "1", numberSort = 1f, bookId = book.id)),
        )
        onlineMangaRepository.insert(onlineManga)
        onlineChapterRepository.insert(
          chapters.map { chapter ->
            OnlineChapter(
              mangaId = onlineManga.id,
              bookId = book.id,
              idx = chapter.index,
              remoteId = chapter.id,
              remoteChapterIndex = chapter.index,
              title = chapter.name,
              pageCount = 1,
              downloaded = false,
              contentKind = chapterKindOrNull(chapter),
            )
          },
        )
        seriesRepository.update(series, false)
      }
    } else {
      val placeholders = chapters.map { onlineLibraryService.chapterPlaceholder(seriesDir, chapterTitle(it)) }

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

      transactionTemplate.executeWithoutResult {
        seriesRepository.insert(series)
        seriesMetadataRepository.insert(seriesMetadataFor(manga, source, contentKind, chapters.size, series.id))
        bookMetadataAggregationRepository.insert(
          BookMetadataAggregation(
            authors = listOfNotNull(manga.author?.takeIf { it.isNotBlank() }?.let { Author(it, "author") }),
            seriesId = series.id,
          ),
        )
        bookRepository.insert(books)
        mediaRepository.insert(
          books.mapIndexed { index, book -> mediaFor(book.id, chapters[index], contentKind) },
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
              pageCount = pageCountFor(kindOf(chapter, contentKind), chapter),
              downloaded = chapter.downloaded ?: false,
              contentKind = chapterKindOrNull(chapter),
            )
          },
        )
        seriesRepository.update(series, false)
      }
    }

    eventPublisher.publishEvent(DomainEvent.SeriesAdded(series))
    onlineReadService.ensureSeriesCover(series.id)
    onlineReadService.ensureBookThumbnails(bookRepository.findAllBySeriesId(series.id).filter { it.deletedDate == null }.map { it.id })
    logger.info { "Subscribed ${manga.title} with ${chapters.size} chapters as series ${series.id}" }

    return OnlineSubscribeResult(series.id, manga.title, chapters.size, false)
  }

  fun sync(seriesId: String): OnlineSyncResult {
    val onlineManga = onlineMangaRepository.findBySeriesIdOrNull(seriesId) ?: throw OnlineException("Not an online series: $seriesId")
    val series = seriesRepository.findByIdOrNull(seriesId) ?: throw OnlineException("Series not found: $seriesId")
    val remote = clients.of(onlineManga.sourceKind).chapters(onlineManga.remoteMangaId.toLong()).sortedBy { it.index }
    if (onlineManga.contentKind == OnlineContentKinds.TEXT) return syncTextual(onlineManga, series, remote)
    val local = onlineChapterRepository.findAllByMangaId(onlineManga.id).associateBy { it.remoteChapterIndex }

    val added = remote.filter { it.index !in local }
    val refreshed =
      remote.mapNotNull { chapter ->
        val existing = local[chapter.index] ?: return@mapNotNull null
        val downloaded = chapter.downloaded ?: false
        val kind = chapterKindOrNull(chapter)
        val pageCount = pageCountFor(kindOf(chapter, onlineManga.contentKind), chapter)
        if (
          existing.pageCount == pageCount &&
          existing.downloaded == downloaded &&
          existing.title == chapter.name &&
          existing.contentKind == kind
        ) {
          null
        } else {
          existing.copy(title = chapter.name, pageCount = pageCount, downloaded = downloaded, contentKind = kind)
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
          books.mapIndexed { index, book -> mediaFor(book.id, added[index], onlineManga.contentKind) },
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
              pageCount = pageCountFor(kindOf(chapter, onlineManga.contentKind), chapter),
              downloaded = chapter.downloaded ?: false,
              contentKind = chapterKindOrNull(chapter),
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
    onlineReadService.ensureBookThumbnails(bookRepository.findAllBySeriesId(seriesId).filter { it.deletedDate == null }.map { it.id })

    logger.info { "Synced ${series.name}: +$added ~${refreshed.size} total=$total" }
    return OnlineSyncResult(seriesId, added.size, refreshed.size, total)
  }

  private fun syncTextual(
    onlineManga: OnlineManga,
    series: Series,
    remote: List<RemoteChapter>,
  ): OnlineSyncResult {
    val book =
      bookRepository.findAllBySeriesId(series.id).singleOrNull()
        ?: throw OnlineException("该书架条目是旧版一章一书结构，请移出书架后到在线源重新加入")

    val local = onlineChapterRepository.findAllByBookId(book.id).associateBy { it.remoteChapterIndex }
    val added = remote.filter { it.index !in local }
    val refreshed =
      remote.mapNotNull { chapter ->
        val existing = local[chapter.index] ?: return@mapNotNull null
        val kind = chapterKindOrNull(chapter)
        if (existing.title == chapter.name && existing.contentKind == kind) null else existing.copy(title = chapter.name, contentKind = kind)
      }

    if (added.isNotEmpty() || refreshed.isNotEmpty()) {
      transactionTemplate.executeWithoutResult {
        if (added.isNotEmpty()) {
          onlineChapterRepository.insert(
            added.map { chapter ->
              OnlineChapter(
                mangaId = onlineManga.id,
                bookId = book.id,
                idx = chapter.index,
                remoteId = chapter.id,
                remoteChapterIndex = chapter.index,
                title = chapter.name,
                pageCount = 1,
                downloaded = false,
                contentKind = chapterKindOrNull(chapter),
              )
            },
          )
        }
        if (refreshed.isNotEmpty()) onlineChapterRepository.update(refreshed)
        mediaRepository.findByIdOrNull(book.id)?.let { media ->
          mediaRepository.update(media.copy(pageCount = 1, extension = textExtension(remote)))
        }
      }
    }

    val total = onlineChapterRepository.findAllByMangaId(onlineManga.id).size
    seriesRepository.update(series.copy(bookCount = total), false)
    onlineMangaRepository.update(onlineManga.copy(lastSyncAt = LocalDateTime.now()))
    onlineReadService.ensureSeriesCover(series.id)
    onlineReadService.ensureBookThumbnails(listOf(book.id))

    logger.info { "Synced text series ${series.name}: +$added ~${refreshed.size} total=$total" }
    return OnlineSyncResult(series.id, added.size, refreshed.size, total)
  }

  fun unsubscribe(
    seriesId: String,
    purgeDownloads: Boolean = false,
  ) {
    val series = seriesRepository.findByIdOrNull(seriesId) ?: throw OnlineException("Series not found: $seriesId")
    val seriesPath = series.path
    val bookIds = bookRepository.findAllIdsBySeriesId(seriesId)
    val onlineManga = onlineMangaRepository.findBySeriesIdOrNull(seriesId)

    transactionTemplate.executeWithoutResult {
      onlineManga?.let {
        onlineChapterRepository.deleteByMangaId(it.id)
        onlineMangaRepository.delete(it.id)
      }
      onlineCacheJobRepository.deleteBySeriesId(seriesId)
      seriesLifecycle.deleteMany(listOf(series))
    }

    removeSeriesArtifacts(seriesPath, bookIds, purgeDownloads)
    logger.info { "Removed online series $seriesId with ${bookIds.size} books (purgeDownloads=$purgeDownloads)" }
  }

  fun cleanupOrphanedSeries(): Int {    val library = onlineLibraryService.findByType() ?: return 0
    val subscribed = onlineMangaRepository.findAll().map { it.seriesId }.toSet()
    val orphans = seriesRepository.findAllByLibraryId(library.id).filter { it.id !in subscribed }
    if (orphans.isEmpty()) return 0
    orphans.forEach { orphan ->
      val path = orphan.path
      val bookIds = bookRepository.findAllIdsBySeriesId(orphan.id)
      runCatching { seriesLifecycle.deleteMany(listOf(orphan)) }
        .onSuccess { removeSeriesArtifacts(path, bookIds, purgeDownloads = false) }
        .onFailure { logger.warn(it) { "Cleaning orphaned online series ${orphan.id} failed" } }
    }
    logger.info { "Cleaned ${orphans.size} orphaned online series in library ${library.id}" }
    return orphans.size
  }

  @EventListener(ApplicationReadyEvent::class)
  fun cleanupOnStartup() {
    runCatching { cleanupOrphanedSeries() }
      .onSuccess { if (it > 0) logger.info { "Removed $it stale online series on startup" } }
      .onFailure { logger.warn(it) { "Startup cleanup of online series failed" } }
  }

  fun listSubscriptions(): List<OnlineManga> = onlineMangaRepository.findAll().toList()

  fun countBySourceKind(sourceKind: String): Int = onlineMangaRepository.findAllBySourceKind(sourceKind).size

  fun removeBySourceKind(
    sourceKind: String,
    purgeDownloads: Boolean = false,
  ): Int {
    val seriesIds = onlineMangaRepository.findAllBySourceKind(sourceKind).map { it.seriesId }
    var removed = 0
    seriesIds.forEach { seriesId ->
      runCatching { unsubscribe(seriesId, purgeDownloads) }
        .onSuccess { removed++ }
        .onFailure { logger.warn(it) { "Removing online series $seriesId of $sourceKind failed" } }
    }
    logger.info { "Removed $removed/${seriesIds.size} online series of $sourceKind (purgeDownloads=$purgeDownloads)" }
    return removed
  }

  fun removeByMissingPlugins(enabled: Set<String>): Int {
    val orphanKinds =
      onlineMangaRepository
        .findAll()
        .map { it.sourceKind }
        .distinct()
        .filter { OnlineSourceIds.isPlugin(it) }
        .filterNot { it.removePrefix("${OnlineSourceIds.PLUGIN}:").substringBefore(':') in enabled }
    return orphanKinds.sumOf { removeBySourceKind(it, purgeDownloads = false) }
  }

  fun adoptAsLocal(sourceKind: String): Int {
    val mangas = onlineMangaRepository.findAllBySourceKind(sourceKind)
    val groups = LinkedHashMap<Path, MutableList<Series>>()
    mangas.forEach { manga ->
      val series = seriesRepository.findByIdOrNull(manga.seriesId) ?: return@forEach
      groups.computeIfAbsent(series.path.parent) { mutableListOf() }.add(series)
    }
    if (groups.isEmpty()) return 0

    var adopted = 0
    groups.forEach { (dir, seriesList) ->
      val library = localLibraryFor(dir) ?: return@forEach
      transactionTemplate.executeWithoutResult {
        seriesList.forEach { series ->
          mangas.firstOrNull { it.seriesId == series.id }?.let { manga ->
            onlineChapterRepository.deleteByMangaId(manga.id)
            onlineMangaRepository.delete(manga.id)
          }
          onlineCacheJobRepository.deleteBySeriesId(series.id)
          seriesRepository.update(series.copy(libraryId = library.id), false)
        }
      }
      seriesList.forEach { cleanPlaceholders(it.path) }
      excludeFromOnlineLibrary(dir)
      taskEmitter.scanLibrary(library.id, priority = HIGHEST_PRIORITY)
      adopted += seriesList.size
      logger.info { "Adopted ${seriesList.size} online series of $sourceKind into local library ${library.name}" }
    }
    return adopted
  }

  fun adoptOrphanDirs(): Int {
    val root = onlineLibraryService.rootPath()
    if (!root.isDirectory()) return 0
    val active =
      onlineMangaRepository
        .findAll()
        .mapNotNull { seriesRepository.findByIdOrNull(it.seriesId)?.path?.parent }
        .toSet()
    val orphans =
      root
        .listDirectoryEntries()
        .filter { it.isDirectory() }
        .filter { it.fileName.toString() != ".thumbnails" }
        .filter { looksLikeSourceDir(it.fileName.toString()) }
        .filterNot { it in active }
        .filter { hasDownload(it) }
    var adopted = 0
    orphans.forEach { dir ->
      val library = localLibraryFor(dir) ?: return@forEach
      excludeFromOnlineLibrary(dir)
      taskEmitter.scanLibrary(library.id, priority = HIGHEST_PRIORITY)
      adopted++
      logger.info { "Adopted orphan download folder ${dir.fileName} as local library ${library.name}" }
    }
    return adopted
  }

  private fun excludeFromOnlineLibrary(dir: Path) {
    val library = onlineLibraryService.findByType() ?: return
    val key = dir.fileName.toString()
    if (key in library.scanDirectoryExclusions) return
    runCatching {
      libraryRepository.update(
        library.copy(
          scanDirectoryExclusions = library.scanDirectoryExclusions + key,
          lastModifiedDate = LocalDateTime.now(),
        ),
      )
    }.onFailure { logger.warn(it) { "Excluding $key from online library scan failed" } }
  }

  private fun hasDownload(dir: Path): Boolean =
    runCatching {
      Files.walk(dir, 3).use { stream -> stream.anyMatch { it.fileName.toString().endsWith(".cbz", ignoreCase = true) } }
    }.getOrDefault(false)

  private fun looksLikeSourceDir(name: String): Boolean = Regex(""".+\([A-Za-z-]{2,8}\)$""").matches(name)

  fun purgeOrphanDirs(): Int {
    val root = onlineLibraryService.rootPath()
    if (!root.isDirectory()) return 0
    val active =
      onlineMangaRepository
        .findAll()
        .mapNotNull { seriesRepository.findByIdOrNull(it.seriesId)?.path?.parent }
        .toSet()
    val adopted = libraryRepository.findAll().filter { it.type == Library.Type.LOCAL }.map { it.path }.toSet()
    val orphans =
      root
        .listDirectoryEntries()
        .filter { it.isDirectory() }
        .filter { it.fileName.toString() != ".thumbnails" }
        .filter { looksLikeSourceDir(it.fileName.toString()) }
        .filterNot { it in active }
        .filterNot { it in adopted }
    var purged = 0
    orphans.forEach { dir ->
      runCatching {
        Files.walk(dir).use { stream ->
          stream.sorted(Comparator.reverseOrder()).forEach { it.deleteIfExists() }
        }
      }.onSuccess {
        purged++
        logger.info { "Purged orphan download folder ${dir.fileName}" }
      }.onFailure { logger.warn(it) { "Purging orphan download folder $dir failed" } }
    }
    return purged
  }

  private fun localLibraryFor(dir: Path): Library? {
    val existing = libraryRepository.findAll().firstOrNull { it.type == Library.Type.LOCAL && it.path == dir }
    if (existing != null) return existing
    if (!dir.isDirectory()) return null
    return runCatching {
      libraryLifecycle.addLibrary(
        Library(
          name = uniqueLibraryName(dir.fileName.toString()),
          root = dir.toUri().toURL(),
          type = Library.Type.LOCAL,
          scanInterval = Library.ScanInterval.DISABLED,
          scanOnStartup = false,
          scanCbx = true,
          scanPdf = false,
          scanEpub = false,
          scanTxt = true,
          hashFiles = false,
          hashPages = false,
          hashKoreader = false,
          analyzeDimensions = false,
          importLocalArtwork = false,
          importBarcodeIsbn = false,
        ),
        scan = false,
      )
    }.onFailure { logger.warn(it) { "Creating local library for $dir failed" } }.getOrNull()
  }

  private fun uniqueLibraryName(base: String): String {
    val taken = libraryRepository.findAll().map { it.name }.toSet()
    if (base !in taken) return base
    var index = 2
    while ("$base ($index)" in taken) index++
    return "$base ($index)"
  }

  private fun cleanPlaceholders(seriesPath: Path) {
    if (!seriesPath.isDirectory()) return
    runCatching {
      seriesPath
        .listDirectoryEntries()
        .filter {
          val name = it.fileName.toString()
          name.endsWith(".placeholder") || name == "cover.jpg"
        }
        .forEach { it.deleteIfExists() }
    }
  }

  private fun mediaFor(
    bookId: String,
    chapter: RemoteChapter,
    sourceKind: String,
  ): Media =
    if (OnlineContentKinds.isTextual(kindOf(chapter, sourceKind))) {
      textMediaFor(bookId, listOf(chapter))
    } else {
      Media(
        status = Media.Status.READY,
        mediaType = MediaType.ZIP.type,
        pageCount = chapter.pageCount.coerceAtLeast(0),
        comment = OnlineContentKinds.MEDIA_COMMENT_IMAGE,
        bookId = bookId,
      )
    }

  private fun textMediaFor(
    bookId: String,
    chapters: List<RemoteChapter>,
  ): Media =
    Media(
      status = Media.Status.READY,
      mediaType = MediaType.TXT.type,
      pageCount = 1,
      comment = OnlineContentKinds.MEDIA_COMMENT_TEXT,
      extension = textExtension(chapters),
      bookId = bookId,
    )

  private fun textExtension(chapters: List<RemoteChapter>): MediaExtensionTxt =
    MediaExtensionTxt(
      chapters =
        chapters.mapIndexed { index, chapter ->
          TxtChapter(
            index = index + 1,
            title = chapter.name.ifBlank { "第 ${index + 1} 章" },
            byteStart = 0,
            byteEnd = 0,
          )
        },
    )

  private fun seriesMetadataFor(
    manga: RemoteManga,
    source: RemoteSource,
    contentKind: String,
    chapterCount: Int,
    seriesId: String,
  ): SeriesMetadata =
    SeriesMetadata(
      status = mapStatus(manga.status),
      title = manga.title,
      titleSort = manga.title,
      summary = manga.description.orEmpty(),
      readingDirection =
        if (OnlineContentKinds.isTextual(contentKind)) {
          SeriesMetadata.ReadingDirection.LEFT_TO_RIGHT
        } else {
          SeriesMetadata.ReadingDirection.RIGHT_TO_LEFT
        },
      publisher = source.name,
      language = source.lang.orEmpty(),
      genres = manga.genre.orEmpty().toSet(),
      totalBookCount = chapterCount,
      seriesId = seriesId,
    )

  private fun kindOf(
    chapter: RemoteChapter,
    sourceKind: String,
  ): String = OnlineContentKinds.resolve(chapter.contentType, sourceKind)

  private fun chapterKindOrNull(chapter: RemoteChapter): String? =
    chapter.contentType?.takeIf { it.isNotBlank() }?.let { OnlineContentKinds.normalize(it) }

  private fun pageCountFor(
    kind: String,
    chapter: RemoteChapter,
  ): Int = if (OnlineContentKinds.isTextual(kind)) 1 else chapter.pageCount

  private fun chapterTitle(chapter: RemoteChapter): String {
    val raw = "%04d-%s".format(chapter.index, chapter.name)
    val trimmed = raw.take(80).trimEnd('.', ' ')
    return if (trimmed.isBlank()) chapter.index.toString().padStart(4, '0') else trimmed
  }

  private fun bookNumber(chapter: RemoteChapter): String =
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

  private fun removeSeriesArtifacts(
    seriesPath: Path,
    bookIds: Collection<String>,
    purgeDownloads: Boolean,
  ) {
    if (seriesPath.exists()) {
      runCatching {
        seriesPath
          .listDirectoryEntries()
          .filter {
            val name = it.fileName.toString()
            name.endsWith(".placeholder") || name == "cover.jpg" || (purgeDownloads && name.endsWith(".cbz", ignoreCase = true))
          }
          .forEach { it.deleteIfExists() }
        if (seriesPath.listDirectoryEntries().isEmpty()) seriesPath.deleteIfExists()
      }
    }
    if (purgeDownloads) {
      val parent = seriesPath.parent
      if (parent != null && parent.parent == onlineLibraryService.rootPath()) {
        runCatching {
          if (parent.isDirectory() && parent.listDirectoryEntries().isEmpty()) parent.deleteIfExists()
        }
      }
    }
    if (bookIds.isEmpty()) return
    val thumbnailDir = onlineLibraryService.thumbnailDir()
    bookIds.forEach { bookId ->
      runCatching { thumbnailDir.resolve("$bookId.jpg").deleteIfExists() }
    }
  }
}
