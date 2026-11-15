package org.lantai.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.lantai.domain.model.AuditLog
import org.lantai.domain.model.AuditLogFilter
import org.lantai.domain.persistence.AuditLogRepository
import org.springdoc.core.converters.models.PageableAsQueryParam
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@RestController
@RequestMapping("api/lantai/v1/audit", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Lantai Audit")
class LantaiAuditController(
  private val auditLogRepository: AuditLogRepository,
) {
  @GetMapping
  @PageableAsQueryParam
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Search audit entries")
  fun search(
    @RequestParam(name = "actorId", required = false) actorId: String?,
    @RequestParam(name = "action", required = false) action: String?,
    @RequestParam(name = "targetType", required = false) targetType: String?,
    @RequestParam(name = "targetId", required = false) targetId: String?,
    @RequestParam(name = "success", required = false) success: Boolean?,
    @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) from: LocalDateTime?,
    @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) to: LocalDateTime?,
    @RequestParam(name = "q", required = false) q: String?,
    @Parameter(hidden = true) pageable: Pageable,
  ): Page<AuditLogDto> {
    val filter =
      AuditLogFilter(
        actorId = actorId?.takeIf { it.isNotBlank() },
        action = action?.takeIf { it.isNotBlank() },
        targetType = targetType?.takeIf { it.isNotBlank() },
        targetId = targetId?.takeIf { it.isNotBlank() },
        success = success,
        from = from?.toUtc(),
        to = to?.toUtc(),
        q = q?.takeIf { it.isNotBlank() },
      )
    return auditLogRepository.search(filter, pageable).map { it.toDto() }
  }

  @GetMapping("actions")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "List the actions present in the audit trail")
  fun actions(): List<AuditActionDto> = auditLogRepository.findDistinctActions().map { AuditActionDto(it) }

  @GetMapping("{id}")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Retrieve a single audit entry")
  fun get(
    @PathVariable id: String,
  ): AuditLogDto =
    auditLogRepository.findByIdOrNull(id)?.toDto()
      ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

  @GetMapping("export", produces = ["text/csv"])
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Export audit entries as CSV")
  fun export(
    @RequestParam(name = "actorId", required = false) actorId: String?,
    @RequestParam(name = "action", required = false) action: String?,
    @RequestParam(name = "targetType", required = false) targetType: String?,
    @RequestParam(name = "targetId", required = false) targetId: String?,
    @RequestParam(name = "success", required = false) success: Boolean?,
    @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) from: LocalDateTime?,
    @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) to: LocalDateTime?,
    @RequestParam(name = "q", required = false) q: String?,
  ): ResponseEntity<String> {
    val filter =
      AuditLogFilter(
        actorId = actorId?.takeIf { it.isNotBlank() },
        action = action?.takeIf { it.isNotBlank() },
        targetType = targetType?.takeIf { it.isNotBlank() },
        targetId = targetId?.takeIf { it.isNotBlank() },
        success = success,
        from = from?.toUtc(),
        to = to?.toUtc(),
        q = q?.takeIf { it.isNotBlank() },
      )
    val entries =
      auditLogRepository
        .search(filter, PageRequest.of(0, MAX_EXPORT, Sort.by(Sort.Order.desc("createdDate"))))
        .content

    val builder = StringBuilder()
    builder.append('\uFEFF')
    builder.append("createdDate,actorEmail,action,targetType,targetId,method,path,status,success,ip,userAgent\n")
    entries.forEach {
      builder
        .append(it.createdDate.escape())
        .append(',')
        .append(it.actorEmail.orEmpty().escape())
        .append(',')
        .append(it.action.escape())
        .append(',')
        .append(it.targetType.orEmpty().escape())
        .append(',')
        .append(it.targetId.orEmpty().escape())
        .append(',')
        .append(it.method.escape())
        .append(',')
        .append(it.path.escape())
        .append(',')
        .append(it.status)
        .append(',')
        .append(it.success)
        .append(',')
        .append(it.ip.orEmpty().escape())
        .append(',')
        .append(it.userAgent.orEmpty().escape())
        .append('\n')
    }

    val filename = "lantai-audit-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.csv"
    return ResponseEntity
      .ok()
      .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
      .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
      .body(builder.toString())
  }

  private fun LocalDateTime.toUtc(): LocalDateTime =
    atZone(ZoneId.systemDefault()).withZoneSameInstant(ZoneId.of("Z")).toLocalDateTime()

  private fun Any.escape(): String = "\"${toString().replace("\"", "\"\"")}\""

  private companion object {
    const val MAX_EXPORT = 5000
  }
}

data class AuditLogDto(
  val id: String,
  val createdDate: LocalDateTime,
  val actorId: String?,
  val actorEmail: String?,
  val action: String,
  val targetType: String?,
  val targetId: String?,
  val method: String,
  val path: String,
  val query: String?,
  val status: Int,
  val success: Boolean,
  val ip: String?,
  val userAgent: String?,
)

data class AuditActionDto(
  val action: String,
)

private fun AuditLog.toDto() =
  AuditLogDto(
    id = id,
    createdDate = createdDate,
    actorId = actorId,
    actorEmail = actorEmail,
    action = action,
    targetType = targetType,
    targetId = targetId,
    method = method,
    path = path,
    query = query,
    status = status,
    success = success,
    ip = ip,
    userAgent = userAgent,
  )
