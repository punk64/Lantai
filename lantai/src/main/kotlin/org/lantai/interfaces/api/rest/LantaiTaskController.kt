package org.lantai.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.lantai.application.tasks.TaskLibraryResolver
import org.lantai.application.tasks.TaskProcessor
import org.lantai.infrastructure.jooq.tasks.LantaiTaskQueryDao
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDateTime

@RestController
@RequestMapping("api/lantai/v1/tasks", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Lantai Tasks")
class LantaiTaskController(
  private val lantaiTaskQueryDao: LantaiTaskQueryDao,
  private val taskProcessor: TaskProcessor,
  private val taskLibraryResolver: TaskLibraryResolver,
) {
  @GetMapping
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "List queued and running tasks, paged")
  fun list(
    @RequestParam(name = "page", defaultValue = "0") page: Int,
    @RequestParam(name = "size", defaultValue = "50") size: Int,
    @RequestParam(name = "type", required = false) type: String?,
    @RequestParam(name = "state", required = false) state: String?,
  ): LantaiTaskListDto {
    val p = page.coerceAtLeast(0)
    val s = size.coerceIn(1, 500)
    val rows = lantaiTaskQueryDao.list(p, s, type, state)
    val total = lantaiTaskQueryDao.countTasks(type, state)
    val libraries = taskLibraryResolver.resolve(rows)
    return LantaiTaskListDto(
      total = total,
      running = lantaiTaskQueryDao.countRunning(),
      queued = lantaiTaskQueryDao.countQueued(),
      byType = lantaiTaskQueryDao.byType(),
      page = p,
      size = s,
      totalPages = maxOf(1, (total + s - 1) / s),
      paused = !taskProcessor.processTasks,
      tasks =
        rows.map {
          val library = libraries[it.id]
          LantaiTaskDto(
            id = it.id,
            type = it.type,
            priority = it.priority,
            groupId = it.groupId,
            owner = it.owner,
            createdDate = it.createdDate,
            libraryId = library?.id,
            libraryName = library?.name,
          )
        },
    )
  }

  @PostMapping("pause")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Stop taking new tasks; running tasks finish")
  fun pause(): Map<String, Boolean> {
    taskProcessor.pause()
    return mapOf("paused" to true)
  }

  @PostMapping("resume")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Resume task processing")
  fun resume(): Map<String, Boolean> {
    taskProcessor.resume()
    return mapOf("paused" to false)
  }

  @DeleteMapping("all")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Delete every queued task; running ones are kept")
  fun clearAll(): Map<String, Int> = mapOf("deleted" to lantaiTaskQueryDao.deleteQueued())

  @DeleteMapping("{taskId}")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Delete a single task by id")
  fun delete(
    @PathVariable taskId: String,
  ): Map<String, Int> = mapOf("deleted" to lantaiTaskQueryDao.deleteById(taskId))
}

data class LantaiTaskDto(
  val id: String,
  val type: String,
  val priority: Int,
  val groupId: String?,
  val owner: String?,
  val createdDate: LocalDateTime,
  val libraryId: String? = null,
  val libraryName: String? = null,
)

data class LantaiTaskListDto(
  val total: Int,
  val running: Int,
  val queued: Int,
  val byType: Map<String, Int>,
  val page: Int,
  val size: Int,
  val totalPages: Int,
  val paused: Boolean,
  val tasks: List<LantaiTaskDto>,
)
