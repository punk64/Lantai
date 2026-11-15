package org.lantai.domain.model

data class TxtChapter(
  val index: Int,
  val title: String,
  val byteStart: Long,
  val byteEnd: Long,
  val charStart: Long = 0,
  val charCount: Long = 0,
) {
  val href: String
    get() = "ch$index"
}

data class MediaExtensionTxt(
  val encoding: String = "UTF-8",
  val totalBytes: Long = 0,
  val totalChars: Long = 0,
  val chapters: List<TxtChapter> = emptyList(),
) : MediaExtension
