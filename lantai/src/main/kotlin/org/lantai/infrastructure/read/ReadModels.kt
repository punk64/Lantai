package org.lantai.infrastructure.read

data class ChapterRefDto(
  val n: Int,
  val title: String,
  val chars: Int,
  val href: String,
)

data class ChapterImageDto(
  val ref: String,
  val bytes: Long,
  val mediaType: String,
)

data class ChapterBundleDto(
  val chapter: Int,
  val title: String,
  val href: String,
  val total: Int,
  val chars: Int,
  val mediaType: String,
  val html: String,
  val images: List<ChapterImageDto>,
  val prev: Int?,
  val next: Int?,
)

data class ReadBookDto(
  val id: String,
  val title: String,
  val seriesTitle: String,
  val number: String,
  val profile: String,
  val chapterCount: Int,
  val readingDirection: String,
  val coverUrl: String,
)

data class ReadProgressDto(
  val chapter: Int,
  val href: String,
  val progression: Double,
  val totalProgression: Double?,
  val completed: Boolean,
)

data class ReadAnnotationDto(
  val id: String,
  val chapter: Int,
  val href: String,
  val color: String,
  val marker: String,
  val note: String,
  val textBefore: String,
  val textHighlight: String,
  val textAfter: String,
  val progression: Double?,
)

data class ReadOutlineEntryDto(
  val n: Int,
  val title: String,
  val depth: Int,
  val chars: Int,
)

data class ReadBootstrapDto(
  val book: ReadBookDto,
  val chapters: List<ChapterRefDto>,
  val from: Int,
  val to: Int,
  val total: Int,
  val progress: ReadProgressDto?,
  val first: ChapterBundleDto,
  val annotations: List<ReadAnnotationDto>,
)

data class ReadChaptersDto(
  val chapters: List<ChapterRefDto>,
  val from: Int,
  val to: Int,
  val total: Int,
  val outline: List<ReadOutlineEntryDto>,
)

data class ReadOutlineVolumeDto(
  val n: Int,
  val title: String,
)

data class ReadOutlinePageDto(
  val entries: List<ChapterRefDto>,
  val from: Int,
  val to: Int,
  val total: Int,
  val volumes: List<ReadOutlineVolumeDto>,
)
