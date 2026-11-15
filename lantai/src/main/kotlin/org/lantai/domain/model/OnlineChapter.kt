package org.lantai.domain.model

import com.github.f4b6a3.tsid.TsidCreator
import java.time.LocalDateTime

data class OnlineChapter(
  val mangaId: String,
  val bookId: String,
  val idx: Int,
  val remoteId: Long = 0,
  val remoteChapterIndex: Int,
  val title: String,
  val pageCount: Int = -1,
  val downloaded: Boolean = false,
  val contentKind: String? = null,
  val id: String = TsidCreator.getTsid256().toString(),
  override val createdDate: LocalDateTime = LocalDateTime.now(),
  override val lastModifiedDate: LocalDateTime = createdDate,
) : Auditable
