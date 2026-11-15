package org.lantai.infrastructure.app

import com.fasterxml.jackson.annotation.JsonFormat
import java.time.LocalDateTime

data class AppHomeDto(
  val coverPath: String,
  @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
  val generatedAt: LocalDateTime,
  val limit: Int,
  val counts: AppHomeCounts,
  val libraries: List<AppLibraryCard>,
  val continueReading: List<AppBookCard>,
  val added: List<AppBookCard>,
  val published: List<AppBookCard>,
  val recentRead: List<AppBookCard>,
  val newSeries: List<AppSeriesCard>,
  val updatedSeries: List<AppSeriesCard>,
)

data class AppHomeCounts(
  val continueReading: Long,
  val added: Long,
  val published: Long,
  val recentRead: Long,
  val newSeries: Long,
  val updatedSeries: Long,
)

data class AppLibraryCard(
  val id: String,
  val name: String,
)

data class AppBookCard(
  val id: String,
  val title: String,
  val seriesId: String,
  val seriesTitle: String,
  val number: String,
  val pages: Int,
  val profile: String,
  val page: Int,
  val completed: Boolean,
  @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
  val at: LocalDateTime?,
)

data class AppSeriesCard(
  val id: String,
  val title: String,
  val books: Int,
  val read: Int,
  @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
  val at: LocalDateTime?,
)

data class AppHomeResult(
  val etag: String,
  val body: AppHomeDto,
)

data class AppCapabilitiesDto(
  val apiVersion: Int = 2,
  val home: Boolean = true,
  val etag: Boolean = true,
  val coverResize: Boolean = true,
  val pagination: AppPaginationLimits = AppPaginationLimits(),
  val coverFormats: List<String> = listOf("jpeg", "png"),
  val incognito: Boolean = true,
  val heartbeat: Boolean = true,
  val offlineSync: Boolean = true,
)

data class AppPaginationLimits(
  val defaultLimit: Int = 16,
  val maxLimit: Int = 50,
)
