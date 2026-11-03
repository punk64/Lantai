package org.lantai.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.lantai.domain.model.ScrapeQueueItem
import org.lantai.domain.persistence.ScrapeMatchRepository
import org.lantai.infrastructure.metadata.scrape.ScrapePreview
import org.lantai.infrastructure.metadata.scrape.ScrapeQueueDto
import org.lantai.infrastructure.metadata.scrape.ScrapeQueueEnqueueResult
import org.lantai.infrastructure.metadata.scrape.ScrapeQueuePage
import org.lantai.infrastructure.metadata.scrape.ScrapeQueueService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("api/lantai/v1/scrape", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Lantai Scrape Queue")
class LantaiScrapeQueueController(
  private val service: ScrapeQueueService,
  private val matchRepository: ScrapeMatchRepository,
) {
  @GetMapping("queue")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Read one page of the global scrape queue")
  fun page(
    @RequestParam(name = "status", required = false) status: String?,
    @RequestParam(name = "offset", defaultValue = "0") offset: Int,
    @RequestParam(name = "limit", defaultValue = "50") limit: Int,
  ): ScrapeQueuePage {
    val parsed =
      status?.takeIf { it.isNotBlank() }?.let {
        runCatching { ScrapeQueueItem.Status.valueOf(it.uppercase()) }.getOrNull()
          ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown status: $status")
      }
    return service.page(parsed, offset, limit)
  }

  @PostMapping("queue")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Add series, a whole library or every series missing metadata to the queue")
  fun enqueue(
    @RequestBody body: ScrapeQueueRequest,
  ): ScrapeQueueEnqueueResult {
    val result =
      when {
        !body.libraryId.isNullOrBlank() -> service.enqueueLibrary(body.libraryId)
        body.missingOnly -> service.enqueueMissing(body.limit ?: DEFAULT_MISSING_LIMIT)
        body.seriesIds.isNotEmpty() ->
          service.enqueueSeries(
            body.seriesIds,
            if (body.seriesIds.size > 1) ScrapeQueueItem.Origin.MULTI else ScrapeQueueItem.Origin.SERIES,
          )

        else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "seriesIds、libraryId、missingOnly 至少给一个")
      }
    if (body.run && result.added > 0) service.start()
    return result
  }

  @PostMapping("queue/start")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Start or resume scraping the queue")
  fun start(): ScrapeQueueRunResult = ScrapeQueueRunResult(started = service.start(), running = true)

  @PostMapping("queue/pause")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Ask the running batch to stop after the current series")
  fun pause(): ScrapeQueueRunResult = ScrapeQueueRunResult(started = service.pause(), running = service.isRunning())

  @PostMapping("queue/{id}/retry")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Reset a queue entry back to queued")
  fun retry(
    @PathVariable id: String,
  ): ScrapeQueueDto =
    try {
      service.retry(id)
    } catch (e: NoSuchElementException) {
      throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message, e)
    }

  @DeleteMapping("queue/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Remove a single queue entry")
  fun remove(
    @PathVariable id: String,
  ) {
    service.remove(id)
  }

  @DeleteMapping("queue")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Clear queue entries by scope: queued, review, failed or all")
  fun clear(
    @RequestParam(name = "scope", defaultValue = "review") scope: String,
  ): ScrapeQueueClearResult = ScrapeQueueClearResult(removed = service.clear(scope))

  @GetMapping("queue/{id}/diff")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Compute the field level difference of a queue entry")
  fun diff(
    @PathVariable id: String,
  ): ScrapePreview =
    try {
      service.preview(id)
    } catch (e: NoSuchElementException) {
      throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message, e)
    }

  @PostMapping("queue/{id}/resolve")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Apply the chosen fields of a queue entry, returns 204 when the entry leaves the queue")
  fun resolve(
    @PathVariable id: String,
    @RequestBody body: ScrapeResolveRequest,
  ): ResponseEntity<ScrapeQueueDto> =
    try {
      service.resolve(id, body.fields.toSet())?.let { ResponseEntity.ok(it) }
        ?: ResponseEntity.noContent().build()
    } catch (e: NoSuchElementException) {
      throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message, e)
    }

  @GetMapping("queue/{id}/history")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "List applied scrape records of the series behind a queue entry")
  fun history(
    @PathVariable id: String,
  ): List<ScrapeHistoryDto> {
    val seriesId =
      try {
        service.find(id).seriesId
      } catch (e: NoSuchElementException) {
        throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message, e)
      }
    return matchRepository.findBySeriesId(seriesId).map {
      ScrapeHistoryDto(
        id = it.id,
        source = it.source,
        score = it.score,
        status = it.status.name,
        appliedFields = it.appliedFields.sorted(),
        createdDate = it.createdDate.toString(),
      )
    }
  }

  private companion object {
    const val DEFAULT_MISSING_LIMIT = 200
  }
}

data class ScrapeQueueRequest(
  val seriesIds: List<String> = emptyList(),
  val libraryId: String? = null,
  val missingOnly: Boolean = false,
  val limit: Int? = null,
  val run: Boolean = false,
)

data class ScrapeQueueRunResult(
  val started: Int,
  val running: Boolean,
)

data class ScrapeQueueClearResult(
  val removed: Int,
)

data class ScrapeResolveRequest(
  val fields: List<String> = emptyList(),
)

data class ScrapeHistoryDto(
  val id: String,
  val source: String,
  val score: Double,
  val status: String,
  val appliedFields: List<String>,
  val createdDate: String,
)
