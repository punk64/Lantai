package org.lantai.domain.model

import com.github.f4b6a3.tsid.TsidCreator
import java.time.LocalDateTime

data class OnlineManga(
  val seriesId: String,
  val remoteMangaId: String,
  val title: String,
  val sourceKind: String = "plugin",
  val contentKind: String = OnlineContentKinds.IMAGE,
  val contentFormat: String = OnlineContentKinds.HTML,
  val coverUrl: String? = null,
  val status: String? = null,
  val lastSyncAt: LocalDateTime? = null,
  val id: String = TsidCreator.getTsid256().toString(),
  override val createdDate: LocalDateTime = LocalDateTime.now(),
  override val lastModifiedDate: LocalDateTime = createdDate,
) : Auditable

object OnlineContentKinds {
  const val IMAGE = "image"
  const val TEXT = "text"
  const val MIXED = "mixed"
  const val HTML = "html"
  const val PLAIN = "plain"
  const val MEDIA_COMMENT_IMAGE = "online"
  const val MEDIA_COMMENT_TEXT = "online-text"

  fun normalize(value: String?): String =
    when (value?.trim()?.lowercase()) {
      TEXT, MIXED -> value.trim().lowercase()
      else -> IMAGE
    }

  fun normalizeFormat(value: String?): String = if (value?.trim()?.lowercase() == PLAIN) PLAIN else HTML

  fun resolve(
    chapterKind: String?,
    sourceKind: String?,
  ): String = normalize(chapterKind?.takeIf { it.isNotBlank() } ?: sourceKind)

  fun isTextual(kind: String?): Boolean = kind == TEXT || kind == MIXED
}
