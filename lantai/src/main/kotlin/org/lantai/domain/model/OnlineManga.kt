package org.lantai.domain.model

import com.github.f4b6a3.tsid.TsidCreator
import java.time.LocalDateTime

data class OnlineManga(
  val seriesId: String,
  val remoteMangaId: String,
  val title: String,
  val sourceKind: String = "suwayomi",
  val coverUrl: String? = null,
  val status: String? = null,
  val lastSyncAt: LocalDateTime? = null,
  val id: String = TsidCreator.getTsid256().toString(),
  override val createdDate: LocalDateTime = LocalDateTime.now(),
  override val lastModifiedDate: LocalDateTime = createdDate,
) : Auditable
