package org.lantai.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.lantai.infrastructure.jooq.tasks.TasksHistoryDao
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDateTime

@RestController
@RequestMapping("api/lantai/v1/tasks/history", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Lantai Tasks")
class LantaiTaskHistoryController(
  private val tasksHistoryDao: TasksHistoryDao,
) {
  @GetMapping
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "List finished tasks, paged")
  fun list(
    @RequestParam(name = "page", defaultValue = "0") page: Int,
    @RequestParam(name = "size", defaultValue = "50") size: Int,
    @RequestParam(name = "type", required = false) type: String?,
    @RequestParam(name = "status", required = false) status: String?,
  ): LantaiTaskHistoryListDto {
    val p = page.coerceAtLeast(0)
    val s = size.coerceIn(1, 500)
    val rows = tasksHistoryDao.list(p, s, type, status)
    val total = tasksHistoryDao.count(type, status)
    return LantaiTaskHistoryListDto(
      total = total,
      page = p,
      size = s,
      totalPages = maxOf(1, (total + s - 1) / s),
      byType = tasksHistoryDao.byType(),
      items =
        rows.map {
          LantaiTaskHistoryDto(
            id = it.id,
            type = it.type,
            priority = it.priority,
            groupId = it.groupId,
            status = it.status,
            createdDate = it.createdDate,
            finishedDate = it.finishedDate,
            durationMs = it.durationMs,
            error = it.error,
          )
        },
    )
  }

  @DeleteMapping
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Delete every finished task record")
  fun clear(): Map<String, Int> = mapOf("deleted" to tasksHistoryDao.clear())
}

data class LantaiTaskHistoryDto(
  val id: String,
  val type: String,
  val priority: Int,
  val groupId: String?,
  val status: String,
  val createdDate: LocalDateTime,
  val finishedDate: LocalDateTime,
  val durationMs: Long,
  val error: String?,
)

data class LantaiTaskHistoryListDto(
  val total: Int,
  val page: Int,
  val size: Int,
  val totalPages: Int,
  val byType: Map<String, Int>,
  val items: List<LantaiTaskHistoryDto>,
)
