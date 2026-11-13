package org.lantai.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import com.fasterxml.jackson.databind.JsonNode
import org.lantai.domain.model.OnlineCacheItem
import org.lantai.domain.model.OnlineCacheJob
import org.lantai.infrastructure.online.OnlineCacheEvictor
import org.lantai.infrastructure.online.OnlineCacheJobService
import org.lantai.infrastructure.online.OnlineException
import org.lantai.infrastructure.online.OnlineLibraryService
import org.lantai.infrastructure.online.OnlineMangaInfo
import org.lantai.infrastructure.online.OnlineSourceRegistry
import org.lantai.infrastructure.online.OnlineSyncService
import org.lantai.infrastructure.online.OnlineFilter
import org.lantai.infrastructure.online.OnlineFilterState
import org.lantai.infrastructure.online.OnlinePreference
import org.lantai.infrastructure.online.OnlineReadService
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime

@RestController
@RequestMapping("api/lantai/v1/online", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Lantai Online")
class LantaiOnlineController(
  private val registry: OnlineSourceRegistry,
  private val libraryService: OnlineLibraryService,
  private val cacheJobService: OnlineCacheJobService,
  private val cacheEvictor: OnlineCacheEvictor,
  private val syncService: OnlineSyncService,
  private val readService: OnlineReadService,
) {
  @PostMapping("library")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Create the managed online library when it has been removed")
  fun ensureLibrary(): OnlineLibraryDto {
    requireEnabled()
    val existed = libraryService.findByType() != null
    val library = libraryService.ensureLibrary()
    return OnlineLibraryDto(library.id, library.name, library.root.toString(), library.type.name, existed)
  }

  @GetMapping("status")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "Aggregated availability of every registered online source")
  fun status(): OnlineStatusDto {
    val summary = registry.summaryAndRefresh()
    return OnlineStatusDto(
      ready = summary.ready,
      contentReady = summary.contentReady,
      providers =
        summary.providers.map {
          OnlineProviderStatusDto(
            kind = it.kind,
            name = it.name,
            installed = it.installed,
            enabled = it.enabled,
            running = it.running,
          )
        },
    )
  }

  @GetMapping("sources")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "List sources with their search/entry toggle state")
  fun sources(): List<OnlineSourceDto> {
    requireEnabled()
    return call { registry.listSources().map { OnlineSourceDto(it.id, it.name, it.lang, it.enabled) } }
  }

  @PutMapping("sources/{sourceId}")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Include or exclude a source from search and entry points")
  fun toggleSource(
    @PathVariable sourceId: String,
    @RequestBody body: OnlineSourceToggleRequest,
  ): List<OnlineSourceDto> {
    requireEnabled()
    call { registry.setEnabled(sourceId, body.enabled) }
    return sources()
  }

  @GetMapping("search")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Search mangas across enabled sources")
  fun search(
    @RequestParam(name = "q") query: String,
    @RequestParam(name = "sourceId", required = false) sourceId: String?,
    @RequestParam(name = "page", defaultValue = "1") page: Int,
  ): OnlineMangaPageDto {
    requireEnabled()
    if (query.isBlank()) return OnlineMangaPageDto(emptyList(), false, page)
    return call {
      val enabled = registry.enabledSourceIds()
      val page1 = registry.search(query.trim(), sourceId, page)
      OnlineMangaPageDto(
        items = page1.items.filter { it.sourceId in enabled }.map { it.toDto() },
        hasNextPage = page1.hasNextPage,
        page = page,
      )
    }
  }

  @PostMapping("browse")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Browse a source by popular or latest, or search inside it")
  fun browse(
    @RequestBody request: OnlineBrowseRequest,
  ): OnlineMangaPageDto {
    requireEnabled()
    return call {
      val enabled = registry.enabledSourceIds()
      val page1 = registry.providerFor(request.sourceId).browse(request.sourceId, request.type, request.q, request.page, request.filters)
      OnlineMangaPageDto(
        items = page1.items.filter { it.sourceId in enabled }.map { it.toDto() },
        hasNextPage = page1.hasNextPage,
        page = request.page,
      )
    }
  }

  @GetMapping("sources/{sourceId}/filters")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "List the runtime filters exposed by a source")
  fun filters(
    @PathVariable sourceId: String,
  ): List<OnlineFilterDto> {
    requireEnabled()
    return call { registry.providerFor(sourceId).filters(sourceId).map { it.toDto() } }
  }

  @GetMapping("sources/{sourceId}/preferences")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Read the configurable preferences of a source")
  fun preferences(
    @PathVariable sourceId: String,
  ): List<OnlinePreferenceDto> {
    requireEnabled()
    return call { registry.providerFor(sourceId).preferences(sourceId).map { it.toDto() } }
  }

  @PatchMapping("sources/{sourceId}/preferences/{position}")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Update one preference of a source and return the refreshed list")
  fun updatePreference(
    @PathVariable sourceId: String,
    @PathVariable position: Int,
    @RequestBody request: OnlinePreferenceChangeRequest,
  ): List<OnlinePreferenceDto> {
    requireEnabled()
    return call {
      val change = mutableMapOf<String, Any>()
      request.checkBoxState?.let { change["checkBoxState"] = it }
      request.switchState?.let { change["switchState"] = it }
      request.editTextState?.let { change["editTextState"] = it }
      request.listState?.let { change["listState"] = it }
      request.multiSelectState?.let { change["multiSelectState"] = it }
      if (change.isEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty preference change")
      registry.providerFor(sourceId).updatePreference(sourceId, position, change).map { it.toDto() }
    }
  }

  @PostMapping("subscribe")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Subscribe to a remote manga and materialize it as a series")
  fun subscribe(
    @RequestBody request: OnlineSubscribeRequest,
  ): OnlineSubscribeDto {
    requireEnabled()
    return call { registry.providerFor(request.sourceId).subscribe(request.sourceId, request.mangaId).let { OnlineSubscribeDto(it.seriesId, it.title, it.bookCount, it.alreadySubscribed) } }
  }

  @PostMapping("sync/{seriesId}")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Synchronize chapters of a subscribed series")
  fun sync(
    @PathVariable seriesId: String,
  ): OnlineSyncDto {
    requireEnabled()
    return call { registry.providerForSeries(seriesId).sync(seriesId).let { OnlineSyncDto(it.seriesId, it.added, it.updated, it.total) } }
  }

  @DeleteMapping("series/{seriesId}")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Unsubscribe a series and remove its placeholder files")
  fun unsubscribe(
    @PathVariable seriesId: String,
    @RequestParam(name = "purgeDownloads", required = false, defaultValue = "false") purgeDownloads: Boolean,
  ) {
    requireEnabled()
    call { registry.providerForSeries(seriesId).unsubscribe(seriesId, purgeDownloads) }
  }

  @PostMapping("cleanup-orphans")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Delete online series whose subscription record no longer exists")
  fun cleanupOrphans(): OnlineCleanupDto = OnlineCleanupDto(syncService.cleanupOrphanedSeries())

  @GetMapping("subscriptions")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "List subscribed online mangas")
  fun subscriptions(): List<OnlineSubscriptionDto> {
    requireEnabled()
    return call {
      registry
        .subscriptions()
        .map { OnlineSubscriptionDto(it.seriesId, it.remoteMangaId, it.title, it.status, it.lastSyncAt?.toString()) }
    }
  }

  @PostMapping("books/{bookId}/download")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Download an online chapter as a local CBZ and re-analyze it")
  fun download(
    @PathVariable bookId: String,
  ): OnlineDownloadDto {
    requireEnabled()
    return call { registry.providerForBook(bookId).download(bookId).let { OnlineDownloadDto(it.bookId, it.pageCount, it.sizeBytes, it.alreadyDownloaded) } }
  }

  @GetMapping("books/{bookId}/content")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Read the chapter body of a text or mixed online source")
  fun content(
    @PathVariable bookId: String,
  ): OnlineContentDto {
    requireEnabled()
    return call {
      val content = readService.contentOrNull(bookId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No online text content for book $bookId")
      OnlineContentDto(content.format, content.content, content.chars)
    }
  }

  @GetMapping("books/{bookId}/assets/{assetId}", produces = [MediaType.ALL_VALUE])
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Fetch an embedded image of a text or mixed online chapter")
  fun asset(
    @PathVariable bookId: String,
    @PathVariable assetId: String,
  ): ResponseEntity<ByteArray> {
    requireEnabled()
    val asset = readService.assetOrNull(bookId, assetId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No asset $assetId for book $bookId")
    val mediaType = runCatching { MediaType.parseMediaType(asset.mediaType) }.getOrDefault(MediaType.APPLICATION_OCTET_STREAM)
    return ResponseEntity.ok().contentType(mediaType).body(asset.bytes)
  }

  @GetMapping("series/{seriesId}/chapters")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "List materialized chapters of a subscription with download state")
  fun chapters(
    @PathVariable seriesId: String,
  ): List<OnlineChapterDto> {
    requireEnabled()
    return call {
      registry
        .providerForSeries(seriesId)
        .chapters(seriesId)
        .map { OnlineChapterDto(it.bookId, it.title, it.index, it.pageCount, it.downloaded) }
    }
  }

  @PostMapping("series/{seriesId}/cache-jobs")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Queue a batch cache job for the chapters of a subscription")
  fun createCacheJob(
    @PathVariable seriesId: String,
    @RequestBody(required = false) request: OnlineCacheCreateDto? = null,
  ): OnlineCacheJobDto =
    call {
      cacheJobService.create(seriesId, request?.bookIds).let { it.toDto() }
    }

  @GetMapping("series/{seriesId}/cache-jobs")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "List cache jobs of a subscription")
  fun cacheJobs(
    @PathVariable seriesId: String,
  ): List<OnlineCacheJobDto> =
    call {
      cacheJobService.list(seriesId).map { it.toDto() }
    }

  @GetMapping("cache-jobs/{jobId}")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Read a cache job")
  fun cacheJob(
    @PathVariable jobId: String,
  ): OnlineCacheJobDto =
    call { cacheJobService.get(jobId).toDto() }

  @GetMapping("cache-jobs/{jobId}/items")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Page the chapters of a cache job")
  fun cacheJobItems(
    @PathVariable jobId: String,
    @RequestParam(name = "page", required = false, defaultValue = "0") page: Int,
    @RequestParam(name = "size", required = false, defaultValue = "100") size: Int,
  ): OnlineCacheItemsDto =
    call {
      val (total, items) = cacheJobService.items(jobId, maxOf(page, 0), size.coerceIn(1, 500))
      OnlineCacheItemsDto(total, items.map { it.toDto() })
    }

  @PostMapping("cache-jobs/{jobId}/pause")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Pause a cache job")
  fun pauseCacheJob(
    @PathVariable jobId: String,
  ): OnlineCacheJobDto =
    call {
      cacheJobService.pause(jobId)
      cacheJobService.get(jobId).toDto()
    }

  @PostMapping("cache-jobs/{jobId}/resume")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Resume a paused cache job")
  fun resumeCacheJob(
    @PathVariable jobId: String,
  ): OnlineCacheJobDto =
    call {
      cacheJobService.resume(jobId)
      cacheJobService.get(jobId).toDto()
    }

  @PostMapping("cache-jobs/{jobId}/cancel")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Cancel a cache job")
  fun cancelCacheJob(
    @PathVariable jobId: String,
  ): OnlineCacheJobDto =
    call {
      cacheJobService.cancel(jobId)
      cacheJobService.get(jobId).toDto()
    }

  @PostMapping("cache-jobs/{jobId}/retry")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Retry the failed chapters of a cache job")
  fun retryCacheJob(
    @PathVariable jobId: String,
  ): OnlineCacheJobDto =
    call {
      cacheJobService.retry(jobId)
      cacheJobService.get(jobId).toDto()
    }

  @DeleteMapping("books/{bookId}/cache")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Delete the downloaded cache of a single online chapter")
  fun evictBookCache(
    @PathVariable bookId: String,
  ): OnlineCacheEvictDto = OnlineCacheEvictDto(bookId, cacheEvictor.evictBook(bookId))

  @GetMapping("manga/{mangaId}")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Read the remote detail of a single manga")
  fun manga(
    @PathVariable mangaId: Long,
    @RequestParam(name = "sourceId", required = false) sourceId: String?,
  ): OnlineMangaDto =
    call {
      val m = registry.providerFor(sourceId).manga(mangaId, sourceId)
      OnlineMangaDto(
        mangaId = m.id,
        sourceId = m.sourceId.orEmpty(),
        title = m.title,
        author = m.author,
        artist = m.artist,
        description = m.description,
        genres = m.genre.orEmpty(),
        status = m.status,
        thumbnailUrl = m.thumbnailUrl,
        inLibrary = m.inLibrary ?: false,
      )
    }

  @GetMapping("manga/{mangaId}/thumbnail")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Proxy the remote cover of a search result")
  fun thumbnail(
    @PathVariable mangaId: Long,
    @RequestParam(name = "sourceId", required = false) sourceId: String?,
  ): ResponseEntity<ByteArray> {
    requireEnabled()
    val bytes = call { registry.providerFor(sourceId).thumbnail(mangaId, sourceId) } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No thumbnail for manga $mangaId")
    return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).body(bytes)
  }

  private fun requireEnabled() {
    if (registry.summary().contentReady) return
    throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Online library is disabled")
  }

  private fun <T> call(block: () -> T): T =
    try {
      block()
    } catch (e: OnlineException) {
      throw ResponseStatusException(HttpStatus.BAD_GATEWAY, e.message, e)
    } catch (e: OnlineException) {
      throw ResponseStatusException(HttpStatus.BAD_GATEWAY, e.message, e)
    }
}

data class OnlineStatusDto(
  val ready: Boolean,
  val contentReady: Boolean,
  val providers: List<OnlineProviderStatusDto>,
)

data class OnlineProviderStatusDto(
  val kind: String,
  val name: String,
  val installed: Boolean,
  val enabled: Boolean,
  val running: Boolean,
)

data class OnlineLibraryDto(
  val id: String,
  val name: String,
  val root: String,
  val type: String,
  val existed: Boolean,
)

data class OnlineSourceDto(
  val id: String,
  val name: String,
  val lang: String,
  val enabled: Boolean,
)

data class OnlineSourceToggleRequest(
  val enabled: Boolean,
)

data class OnlineMangaDto(
  val mangaId: Long,
  val sourceId: String,
  val title: String,
  val author: String?,
  val artist: String?,
  val description: String?,
  val genres: List<String>,
  val status: String?,
  val thumbnailUrl: String?,
  val inLibrary: Boolean,
)

data class OnlineSubscribeRequest(
  val sourceId: String,
  val mangaId: Long,
)

data class OnlineSubscribeDto(
  val seriesId: String,
  val title: String,
  val bookCount: Int,
  val alreadySubscribed: Boolean,
)

data class OnlineSyncDto(
  val seriesId: String,
  val added: Int,
  val updated: Int,
  val total: Int,
)

data class OnlineSubscriptionDto(
  val seriesId: String,
  val remoteMangaId: String,
  val title: String,
  val status: String?,
  val lastSyncAt: String?,
)

data class OnlineCleanupDto(
  val removed: Int,
)

data class OnlineDownloadDto(
  val bookId: String,
  val pageCount: Int,
  val sizeBytes: Long,
  val alreadyDownloaded: Boolean,
)

data class OnlineChapterDto(
  val bookId: String,
  val title: String,
  val index: Int,
  val pageCount: Int,
  val downloaded: Boolean,
)

data class OnlineContentDto(
  val format: String,
  val content: String,
  val chars: Int,
)

data class OnlineCacheCreateDto(
  val bookIds: List<String>? = null,
)

data class OnlineCacheJobDto(
  val id: String,
  val seriesId: String,
  val status: String,
  val total: Int,
  val done: Int,
  val failed: Int,
  val createdDate: LocalDateTime,
  val lastModifiedDate: LocalDateTime,
)

data class OnlineCacheItemDto(
  val id: String,
  val bookId: String,
  val index: Int,
  val title: String,
  val status: String,
  val error: String,
  val pageCount: Int,
  val sizeBytes: Long,
)

data class OnlineCacheItemsDto(
  val total: Long,
  val items: List<OnlineCacheItemDto>,
)

data class OnlineCacheEvictDto(
  val bookId: String,
  val evicted: Boolean,
)

data class OnlineMangaPageDto(
  val items: List<OnlineMangaDto>,
  val hasNextPage: Boolean,
  val page: Int,
)

data class OnlineBrowseRequest(
  val sourceId: String,
  val type: String = "POPULAR",
  val q: String? = null,
  val page: Int = 1,
  val filters: List<OnlineFilterState> = emptyList(),
)

data class OnlineFilterDto(
  val position: Int,
  val type: String,
  val name: String,
  val defaultValue: JsonNode?,
  val values: List<String>,
  val children: List<OnlineFilterDto>,
)

data class OnlinePreferenceDto(
  val position: Int,
  val type: String,
  val key: String,
  val title: String,
  val summary: String?,
  val value: JsonNode?,
  val defaultValue: JsonNode?,
  val enabled: Boolean,
  val visible: Boolean,
  val entries: List<String>,
  val entryValues: List<String>,
)

data class OnlinePreferenceChangeRequest(
  val checkBoxState: Boolean? = null,
  val switchState: Boolean? = null,
  val editTextState: String? = null,
  val listState: String? = null,
  val multiSelectState: List<String>? = null,
)

private fun OnlineCacheJob.toDto() =
  OnlineCacheJobDto(
    id = id,
    seriesId = seriesId,
    status = status.name,
    total = total,
    done = done,
    failed = failed,
    createdDate = createdDate,
    lastModifiedDate = lastModifiedDate,
  )

private fun OnlineCacheItem.toDto() =
  OnlineCacheItemDto(
    id = id,
    bookId = bookId,
    index = chapIndex,
    title = title,
    status = status.name,
    error = error,
    pageCount = pageCount,
    sizeBytes = sizeBytes,
  )

private fun OnlineFilter.toDto(): OnlineFilterDto =
  OnlineFilterDto(
    position = position,
    type = type,
    name = name,
    defaultValue = defaultValue,
    values = values,
    children = children.map { it.toDto() },
  )

private fun OnlinePreference.toDto(): OnlinePreferenceDto =
  OnlinePreferenceDto(
    position = position,
    type = type.removeSuffix("Preference").uppercase(),
    key = key,
    title = title,
    summary = summary,
    value = value,
    defaultValue = defaultValue,
    enabled = enabled,
    visible = visible,
    entries = entries,
    entryValues = entryValues,
  )

private fun OnlineMangaInfo.toDto() =
  OnlineMangaDto(
    mangaId = mangaId,
    sourceId = sourceId,
    title = title,
    author = author,
    artist = artist,
    description = description,
    genres = genres,
    status = status,
    thumbnailUrl = thumbnailUrl,
    inLibrary = inLibrary,
  )
