package org.lantai.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import com.fasterxml.jackson.databind.JsonNode
import org.lantai.infrastructure.online.OnlineException
import org.lantai.infrastructure.online.OnlineMangaInfo
import org.lantai.infrastructure.online.OnlineSourceRegistry
import org.lantai.infrastructure.online.SuwayomiClient
import org.lantai.infrastructure.online.SuwayomiException
import org.lantai.infrastructure.online.SuwayomiFilter
import org.lantai.infrastructure.online.SuwayomiFilterState
import org.lantai.infrastructure.online.SuwayomiPreference
import org.lantai.infrastructure.online.OnlineSyncService
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

@RestController
@RequestMapping("api/lantai/v1/online", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Lantai Online")
class LantaiOnlineController(
  private val registry: OnlineSourceRegistry,
  private val client: SuwayomiClient,
  private val syncService: OnlineSyncService,
) {
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
      val page1 = registry.default().search(query.trim(), sourceId, page)
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
      val page1 = registry.default().browse(request.sourceId, request.type, request.q, request.page, request.filters)
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
    return call { registry.default().filters(sourceId).map { it.toDto() } }
  }

  @GetMapping("sources/{sourceId}/preferences")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Read the configurable preferences of a source")
  fun preferences(
    @PathVariable sourceId: String,
  ): List<OnlinePreferenceDto> {
    requireEnabled()
    return call { syncService.preferences(sourceId).map { it.toDto() } }
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
      syncService.updatePreference(sourceId, position, change).map { it.toDto() }
    }
  }

  @PostMapping("subscribe")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Subscribe to a remote manga and materialize it as a series")
  fun subscribe(
    @RequestBody request: OnlineSubscribeRequest,
  ): OnlineSubscribeDto {
    requireEnabled()
    return call { registry.default().subscribe(request.sourceId, request.mangaId).let { OnlineSubscribeDto(it.seriesId, it.title, it.bookCount, it.alreadySubscribed) } }
  }

  @PostMapping("sync/{seriesId}")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Synchronize chapters of a subscribed series")
  fun sync(
    @PathVariable seriesId: String,
  ): OnlineSyncDto {
    requireEnabled()
    return call { registry.default().sync(seriesId).let { OnlineSyncDto(it.seriesId, it.added, it.updated, it.total) } }
  }

  @DeleteMapping("series/{seriesId}")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Unsubscribe a series and remove its placeholder files")
  fun unsubscribe(
    @PathVariable seriesId: String,
    @RequestParam(name = "purgeDownloads", required = false, defaultValue = "false") purgeDownloads: Boolean,
  ) {
    requireEnabled()
    call { registry.default().unsubscribe(seriesId, purgeDownloads) }
  }

  @GetMapping("subscriptions")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "List subscribed online mangas")
  fun subscriptions(): List<OnlineSubscriptionDto> {
    requireEnabled()
    return call {
      registry
        .default()
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
    return call { registry.default().download(bookId).let { OnlineDownloadDto(it.bookId, it.pageCount, it.sizeBytes, it.alreadyDownloaded) } }
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
        .default()
        .chapters(seriesId)
        .map { OnlineChapterDto(it.bookId, it.title, it.index, it.pageCount, it.downloaded) }
    }
  }

  @GetMapping("manga/{mangaId}")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Read the remote detail of a single manga")
  fun manga(
    @PathVariable mangaId: Long,
  ): OnlineMangaDto =
    call {
      val m = syncService.manga(mangaId)
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
  ): ResponseEntity<ByteArray> {
    requireEnabled()
    val bytes = call { registry.default().thumbnail(mangaId) } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No thumbnail for manga $mangaId")
    return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).body(bytes)
  }

  private fun requireEnabled() {
    if (!client.isEnabled()) throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Online library is disabled")
  }

  private fun <T> call(block: () -> T): T =
    try {
      block()
    } catch (e: OnlineException) {
      throw ResponseStatusException(HttpStatus.BAD_GATEWAY, e.message, e)
    } catch (e: SuwayomiException) {
      throw ResponseStatusException(HttpStatus.BAD_GATEWAY, e.message, e)
    }
}

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
  val filters: List<SuwayomiFilterState> = emptyList(),
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

private fun SuwayomiFilter.toDto(): OnlineFilterDto =
  OnlineFilterDto(
    position = position,
    type = type,
    name = name,
    defaultValue = defaultValue,
    values = values,
    children = children.map { it.toDto() },
  )

private fun SuwayomiPreference.toDto(): OnlinePreferenceDto =
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
