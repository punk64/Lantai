package org.lantai.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.lantai.infrastructure.configuration.LantaiProperties
import org.lantai.infrastructure.jooq.main.LantaiExportBookRow
import org.lantai.infrastructure.jooq.main.LantaiExportLibraryRow
import org.lantai.infrastructure.jooq.main.LantaiExportQueryDao
import org.lantai.infrastructure.jooq.main.LantaiExportSeriesRow
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.sql.DataSource

@RestController
@RequestMapping("api/lantai/v1/system")
@Tag(name = "Lantai System")
class LantaiSystemBackupController(
  private val lantaiProperties: LantaiProperties,
  private val lantaiExportQueryDao: LantaiExportQueryDao,
  @Qualifier("sqliteDataSourceRW") private val mainDataSource: DataSource,
  @Qualifier("tasksDataSourceRW") private val tasksDataSource: DataSource,
) {
  @PostMapping("backup", produces = [MediaType.APPLICATION_JSON_VALUE])
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Snapshot the main and tasks SQLite databases")
  fun backup(): LantaiBackupDto {
    val stamp = STAMP.format(LocalDateTime.now())
    val dir = configDir().resolve("backups").resolve(stamp)
    Files.createDirectories(dir)
    val files =
      listOfNotNull(
        snapshot("database", lantaiProperties.database.file, mainDataSource, dir),
        snapshot("tasks", lantaiProperties.tasksDb.file, tasksDataSource, dir),
      )
    return LantaiBackupDto(
      name = "backup-$stamp",
      directory = dir.toString(),
      createdAt = Instant.now(),
      totalBytes = files.sumOf { it.bytes },
      files = files,
    )
  }

  @GetMapping("export", produces = [MediaType.APPLICATION_JSON_VALUE])
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Export the library inventory as JSON")
  fun export(): ResponseEntity<LantaiExportDto> {
    val libraries = lantaiExportQueryDao.libraries()
    val series = lantaiExportQueryDao.series()
    val books = lantaiExportQueryDao.books()
    val payload =
      LantaiExportDto(
        exportedAt = Instant.now(),
        counts = LantaiExportCountsDto(libraries.size, series.size, books.size),
        libraries = libraries,
        series = series,
        books = books,
      )
    val filename = "lantai-library-${DATE.format(LocalDate.now())}.json"
    return ResponseEntity
      .ok()
      .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
      .contentType(MediaType.APPLICATION_JSON)
      .body(payload)
  }

  private fun configDir(): Path =
    Paths.get(lantaiProperties.configDir ?: ".").toAbsolutePath().normalize()

  private fun snapshot(label: String, file: String, dataSource: DataSource, dir: Path): LantaiBackupFileDto? {
    if (file.isBlank() || file.contains(":memory:") || file.contains("mode=memory")) return null
    if (!Files.exists(Paths.get(file))) return null
    val target = dir.resolve("$label.sqlite")
    Files.deleteIfExists(target)
    val literal = target.toString().replace('\\', '/').replace("'", "''")
    dataSource.connection.use { connection ->
      connection.createStatement().use { statement -> statement.execute("VACUUM INTO '$literal'") }
    }
    return LantaiBackupFileDto(label = label, file = target.toString(), bytes = Files.size(target))
  }

  companion object {
    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
  }
}

data class LantaiBackupDto(
  val name: String,
  val directory: String,
  val createdAt: Instant,
  val totalBytes: Long,
  val files: List<LantaiBackupFileDto>,
)

data class LantaiBackupFileDto(
  val label: String,
  val file: String,
  val bytes: Long,
)

data class LantaiExportDto(
  val exportedAt: Instant,
  val counts: LantaiExportCountsDto,
  val libraries: List<LantaiExportLibraryRow>,
  val series: List<LantaiExportSeriesRow>,
  val books: List<LantaiExportBookRow>,
)

data class LantaiExportCountsDto(
  val libraries: Int,
  val series: Int,
  val books: Int,
)
