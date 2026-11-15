package org.lantai.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.lantai.infrastructure.configuration.LantaiProperties
import org.lantai.infrastructure.jooq.main.LantaiSystemQueryDao
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant

@RestController
@RequestMapping("api/lantai/v1", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Lantai System")
class LantaiSystemController(
  private val lantaiSystemQueryDao: LantaiSystemQueryDao,
  private val lantaiProperties: LantaiProperties,
  private val buildProperties: ObjectProvider<BuildProperties>,
) {
  @GetMapping("system")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Runtime and storage status")
  fun system(): LantaiSystemDto {
    val runtime = Runtime.getRuntime()
    val jvm = ManagementFactory.getRuntimeMXBean()
    val os = ManagementFactory.getOperatingSystemMXBean()
    val dir = Paths.get(lantaiProperties.configDir ?: ".").toAbsolutePath().normalize()
    val store =
      runCatching {
        Files
          .getFileStore(dir)
          .let { LantaiStorageDto(dir.toString(), it.totalSpace, it.usableSpace) }
      }.getOrNull()
    return LantaiSystemDto(
      version = buildProperties.getIfAvailable()?.version ?: "unknown",
      jvm =
        LantaiJvmDto(
          version = System.getProperty("java.version"),
          vendor = System.getProperty("java.vendor"),
          startTime = Instant.ofEpochMilli(jvm.startTime),
          uptimeMs = jvm.uptime,
          heapUsed = runtime.totalMemory() - runtime.freeMemory(),
          heapMax = runtime.maxMemory(),
          availableProcessors = runtime.availableProcessors(),
          threadCount = ManagementFactory.getThreadMXBean().threadCount,
          systemLoadAverage = os.systemLoadAverage,
        ),
      os =
        LantaiOsDto(
          name = System.getProperty("os.name"),
          version = System.getProperty("os.version"),
          arch = System.getProperty("os.arch"),
          timeZone = java.util.TimeZone.getDefault().id,
        ),
      storage = store,
      counts =
        LantaiCountsDto(
          libraries = lantaiSystemQueryDao.countLibraries(),
          series = lantaiSystemQueryDao.countSeries(),
          books = lantaiSystemQueryDao.countBooks(),
          sessions = lantaiSystemQueryDao.countSessions(),
          trashEntries = lantaiSystemQueryDao.countTrashEntries(),
        ),
    )
  }
}

data class LantaiSystemDto(
  val version: String,
  val jvm: LantaiJvmDto,
  val os: LantaiOsDto,
  val storage: LantaiStorageDto?,
  val counts: LantaiCountsDto,
)

data class LantaiJvmDto(
  val version: String,
  val vendor: String,
  val startTime: Instant,
  val uptimeMs: Long,
  val heapUsed: Long,
  val heapMax: Long,
  val availableProcessors: Int,
  val threadCount: Int,
  val systemLoadAverage: Double,
)

data class LantaiOsDto(
  val name: String,
  val version: String,
  val arch: String,
  val timeZone: String,
)

data class LantaiStorageDto(
  val path: String,
  val totalBytes: Long,
  val usableBytes: Long,
)

data class LantaiCountsDto(
  val libraries: Int,
  val series: Int,
  val books: Int,
  val sessions: Int,
  val trashEntries: Int,
)
