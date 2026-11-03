package org.lantai.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.lantai.domain.model.Book
import org.lantai.domain.model.Series
import org.lantai.domain.model.TrashEntry
import org.lantai.domain.persistence.BookRepository
import org.lantai.domain.persistence.LibraryRepository
import org.lantai.domain.persistence.SeriesRepository
import org.lantai.domain.persistence.TrashEntryRepository
import org.lantai.domain.service.BookLifecycle
import org.lantai.domain.service.OnlineContentGuard
import org.lantai.domain.service.SeriesLifecycle
import org.lantai.domain.service.TrashBin
import org.lantai.infrastructure.configuration.LantaiSettingsProvider
import org.lantai.infrastructure.online.OnlineCacheEvictor
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime

@RestController
@RequestMapping("api/lantai/v1/trash", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Lantai Trash")
class LantaiTrashController(
  private val trashBin: TrashBin,
  private val trashEntryRepository: TrashEntryRepository,
  private val seriesRepository: SeriesRepository,
  private val bookRepository: BookRepository,
  private val libraryRepository: LibraryRepository,
  private val seriesLifecycle: SeriesLifecycle,
  private val bookLifecycle: BookLifecycle,
  private val onlineContentGuard: OnlineContentGuard,
  private val lantaiSettingsProvider: LantaiSettingsProvider,
  private val onlineCacheEvictor: OnlineCacheEvictor,
) {
  @GetMapping
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "List trash entries of one or all libraries")
  fun list(
    @RequestParam(name = "libraryId", required = false) libraryId: String?,
  ): TrashListDto {
    val entries =
      if (libraryId.isNullOrBlank()) {
        trashEntryRepository.findAll()
      } else {
        trashEntryRepository.findAllByLibraryId(libraryId)
      }
    val totalBytes = entries.sumOf { it.sizeBytes }
    val maxBytes =
      if (libraryId.isNullOrBlank()) {
        lantaiSettingsProvider.trashMaxBytesDefault
      } else {
        libraryRepository.findByIdOrNull(libraryId)?.let {
          trashBin.effectiveMaxBytes(it, lantaiSettingsProvider.trashMaxBytesDefault)
        } ?: 0L
      }
    return TrashListDto(
      enabled = maxBytes > 0,
      maxBytes = maxBytes,
      totalBytes = totalBytes,
      entries = entries.map { it.toDto() },
    )
  }

  @PostMapping("{entryId}/restore")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Restore a trash entry back to its library")
  fun restore(
    @PathVariable entryId: String,
  ) {
    val entry = trashEntryRepository.findByIdOrNull(entryId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    trashBin.restore(entry)
  }

  @DeleteMapping("{entryId}")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Permanently delete a trash entry and its files")
  fun purge(
    @PathVariable entryId: String,
  ) {
    val entry = trashEntryRepository.findByIdOrNull(entryId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    trashBin.purge(entry)
  }

  @DeleteMapping
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Empty the trash of one or all libraries")
  fun purgeAll(
    @RequestParam(name = "libraryId", required = false) libraryId: String?,
  ) {
    if (libraryId.isNullOrBlank()) {
      trashEntryRepository.findAll().forEach { trashBin.purge(it) }
    } else {
      trashBin.purgeAll(libraryId)
    }
  }

  @GetMapping("settings")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Read trash settings")
  fun getSettings(): TrashSettingsDto =
    TrashSettingsDto(
      maxBytesDefault = lantaiSettingsProvider.trashMaxBytesDefault,
      autoClean = lantaiSettingsProvider.trashAutoClean,
      retentionHours = lantaiSettingsProvider.trashRetentionHours,
    )

  @PutMapping("settings")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Update trash settings")
  fun updateSettings(
    @RequestBody dto: TrashSettingsDto,
  ): TrashSettingsDto {
    lantaiSettingsProvider.trashMaxBytesDefault = dto.maxBytesDefault
    lantaiSettingsProvider.trashAutoClean = dto.autoClean
    lantaiSettingsProvider.trashRetentionHours = dto.retentionHours
    return getSettings()
  }

  @DeleteMapping("cache/{seriesId}")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Delete downloaded cache of an online series, chapters remain readable through the source")
  fun evictCache(
    @PathVariable seriesId: String,
  ): CacheEvictDto = CacheEvictDto(onlineCacheEvictor.evictSeries(seriesId))

  @PutMapping("quota/{libraryId}")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Set the trash quota of a library, zero means inheriting the global default")
  fun setQuota(
    @PathVariable libraryId: String,
    @RequestParam(name = "maxBytes") maxBytes: Long?,
  ): LibraryQuotaDto {
    val library = libraryRepository.findByIdOrNull(libraryId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    libraryRepository.update(library.copy(trashMaxBytes = maxBytes?.takeIf { it > 0 }))
    return LibraryQuotaDto(libraryId, maxBytes?.takeIf { it > 0 })
  }

  @GetMapping("excluded")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "List series removed from library but still present on disk")
  fun listExcluded(
    @RequestParam(name = "libraryId", required = false) libraryId: String?,
  ): List<ExcludedSeriesDto> {
    val series =
      if (libraryId.isNullOrBlank()) {
        seriesRepository.findAllExcluded()
      } else {
        seriesRepository.findAllExcludedByLibraryId(libraryId)
      }
    return series.map { it.toExcludedDto() }
  }

  @PutMapping("excluded/{seriesId}")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Remove a series from the library, keeping its files untouched")
  fun exclude(
    @PathVariable seriesId: String,
  ) {
    if (onlineContentGuard.isOnlineSeries(seriesId)) {
      throw ResponseStatusException(HttpStatus.CONFLICT, "Series belongs to an online library, use cache eviction instead")
    }
    val series = seriesRepository.findByIdOrNull(seriesId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    seriesLifecycle.excludeMany(listOf(series))
  }

  @DeleteMapping("excluded/{seriesId}")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Bring back a series previously removed from the library")
  fun unexclude(
    @PathVariable seriesId: String,
  ) {
    val series = seriesRepository.findByIdOrNull(seriesId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    seriesLifecycle.restoreExcluded(listOf(series))
  }

  @GetMapping("excluded/books")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "List books removed from library but still present on disk")
  fun listExcludedBooks(
    @RequestParam(name = "libraryId", required = false) libraryId: String?,
  ): List<ExcludedBookDto> {
    val books =
      if (libraryId.isNullOrBlank()) {
        bookRepository.findAllExcluded()
      } else {
        bookRepository.findAllExcludedByLibraryId(libraryId)
      }
    return books.map { it.toExcludedDto() }
  }

  @PutMapping("excluded/books/{bookId}")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Remove a book from the library, keeping its file untouched")
  fun excludeBook(
    @PathVariable bookId: String,
  ) {
    if (onlineContentGuard.isOnlineBook(bookId)) {
      throw ResponseStatusException(HttpStatus.CONFLICT, "Book belongs to an online library, use cache eviction instead")
    }
    val book = bookRepository.findByIdOrNull(bookId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    bookLifecycle.excludeMany(listOf(book))
  }

  @DeleteMapping("excluded/books/{bookId}")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Bring back a book previously removed from the library")
  fun unexcludeBook(
    @PathVariable bookId: String,
  ) {
    val book = bookRepository.findByIdOrNull(bookId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    bookLifecycle.restoreExcluded(listOf(book))
  }

  private fun TrashEntry.toDto(): TrashEntryDto {
    val series =
      when (itemType) {
        TrashEntry.ItemType.SERIES -> seriesId?.let { seriesRepository.findByIdOrNull(it) }
        TrashEntry.ItemType.BOOK -> seriesId?.let { seriesRepository.findByIdOrNull(it) }
      }
    val title =
      when (itemType) {
        TrashEntry.ItemType.SERIES -> series?.name
        TrashEntry.ItemType.BOOK -> bookId?.let { bookRepository.findByIdOrNull(it)?.name }
      } ?: ""
    return TrashEntryDto(
      id = id,
      itemType = itemType.name,
      libraryId = libraryId,
      seriesId = seriesId,
      bookId = bookId,
      title = title,
      originalPath = originalPath,
      sizeBytes = sizeBytes,
      deletedDate = deletedDate,
      coverSeriesId = series?.id,
    )
  }

  private fun Series.toExcludedDto() =
    ExcludedSeriesDto(
      id = id,
      libraryId = libraryId,
      name = name,
      bookCount = bookCount,
      excludedDate = excludedDate ?: LocalDateTime.now(),
    )

  private fun Book.toExcludedDto() =
    ExcludedBookDto(
      id = id,
      libraryId = libraryId,
      seriesId = seriesId,
      name = name,
      sizeBytes = fileSize,
      excludedDate = excludedDate ?: LocalDateTime.now(),
    )
}

data class TrashEntryDto(
  val id: String,
  val itemType: String,
  val libraryId: String,
  val seriesId: String?,
  val bookId: String?,
  val title: String,
  val originalPath: String,
  val sizeBytes: Long,
  val deletedDate: LocalDateTime,
  val coverSeriesId: String?,
)

data class TrashListDto(
  val enabled: Boolean,
  val maxBytes: Long,
  val totalBytes: Long,
  val entries: List<TrashEntryDto>,
)

data class TrashSettingsDto(
  val maxBytesDefault: Long,
  val autoClean: Boolean,
  val retentionHours: Int,
)

data class ExcludedSeriesDto(
  val id: String,
  val libraryId: String,
  val name: String,
  val bookCount: Int,
  val excludedDate: LocalDateTime,
)

data class ExcludedBookDto(
  val id: String,
  val libraryId: String,
  val seriesId: String,
  val name: String,
  val sizeBytes: Long,
  val excludedDate: LocalDateTime,
)

data class CacheEvictDto(
  val evictedChapters: Int,
)

data class LibraryQuotaDto(
  val libraryId: String,
  val maxBytes: Long?,
)
