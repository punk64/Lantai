package org.lantai.interfaces.api.rest

import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.annotation.PreDestroy
import org.lantai.domain.service.LogFileInfo
import org.lantai.domain.service.LogFileReader
import org.lantai.domain.service.LogPage
import org.lantai.infrastructure.configuration.LogLevelRegistry
import org.lantai.infrastructure.configuration.LoggerLevel
import org.springframework.core.io.FileSystemResource
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

@RestController
@RequestMapping("api/lantai/v1/logs", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Lantai Logs")
class LantaiLogController(
  private val logFileReader: LogFileReader,
  private val logLevelRegistry: LogLevelRegistry,
) {
  private val scheduler: ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor { runnable ->
      Thread(runnable, "lantai-log-stream").apply { isDaemon = true }
    }

  private val activeStreams = AtomicInteger(0)

  @PreDestroy
  fun shutdown() {
    scheduler.shutdownNow()
  }

  @GetMapping("files")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "List available log files")
  fun files(): List<LogFileInfo> = logFileReader.listFiles()

  @GetMapping("entries")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Read structured log entries, cursor based")
  fun entries(
    @RequestParam(name = "file", required = false) file: String?,
    @RequestParam(name = "cursor", required = false) cursor: Long?,
    @RequestParam(name = "limit", required = false) limit: Int?,
    @RequestParam(name = "level", required = false) level: String?,
    @RequestParam(name = "q", required = false) q: String?,
    @RequestParam(name = "from", required = false) from: String?,
    @RequestParam(name = "to", required = false) to: String?,
  ): LogPage = logFileReader.read(resolveFile(file), cursor, limit, level, q, from, to)

  @GetMapping("files/{name}/download")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Download a raw log file")
  fun download(
    @PathVariable name: String,
  ): ResponseEntity<FileSystemResource> {
    val path = logFileReader.resolve(name)
    return ResponseEntity
      .ok()
      .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
      .contentType(MediaType.APPLICATION_OCTET_STREAM)
      .contentLength(Files.size(path))
      .body(FileSystemResource(path))
  }

  @GetMapping("stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Stream new log entries")
  fun stream(
    @RequestParam(name = "file", required = false) file: String?,
    @RequestParam(name = "level", required = false) level: String?,
    @RequestParam(name = "q", required = false) q: String?,
  ): SseEmitter {
    if (activeStreams.incrementAndGet() > MAX_STREAMS) {
      activeStreams.decrementAndGet()
      throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many concurrent log streams")
    }

    val name = resolveFile(file)
    val emitter = SseEmitter(0L)
    var cursor = Files.size(logFileReader.resolve(name))
    var ticks = 0

    val task =
      scheduler.scheduleAtFixedRate(
        {
          try {
            val page: LogPage = logFileReader.read(name, cursor, STREAM_BATCH, level, q, null, null)
            cursor = page.nextCursor
            ticks++
            when {
              page.entries.isNotEmpty() -> emitter.send(SseEmitter.event().name("log").data(page.entries))
              ticks % HEARTBEAT_EVERY == 0 -> emitter.send(SseEmitter.event().comment("heartbeat"))
            }
          } catch (e: Exception) {
            logger.warn(e) { "Log stream terminated for file $name" }
            runCatching { emitter.completeWithError(e) }
            throw e
          }
        },
        0,
        1,
        TimeUnit.SECONDS,
      )

    val stop = {
      task.cancel(false)
      activeStreams.decrementAndGet()
    }
    emitter.onCompletion { stop() }
    emitter.onTimeout { stop() }
    emitter.onError { stop() }

    return emitter
  }

  @GetMapping("levels")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "List loggers and their levels")
  fun levels(): List<LoggerLevel> = logLevelRegistry.list()

  @PutMapping("levels")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Change logger levels at runtime, use INHERIT to restore the default")
  fun updateLevels(
    @RequestBody body: LogLevelsUpdateDto,
  ): List<LoggerLevel> = logLevelRegistry.set(body.levels)

  @DeleteMapping("levels")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Restore logger levels to their default")
  fun resetLevels(
    @RequestBody body: LogLevelsResetDto,
  ): List<LoggerLevel> = logLevelRegistry.reset(body.names)

  private fun resolveFile(file: String?): String =
    file?.takeIf { it.isNotBlank() }
      ?: logFileReader.defaultFile()
      ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No log file available")

  private companion object {
    const val MAX_STREAMS = 5
    const val STREAM_BATCH = 200
    const val HEARTBEAT_EVERY = 15
  }
}

data class LogLevelsUpdateDto(
  val levels: Map<String, String>,
)

data class LogLevelsResetDto(
  val names: List<String>,
)
