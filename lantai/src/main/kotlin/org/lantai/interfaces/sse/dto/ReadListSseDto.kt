package org.lantai.interfaces.sse.dto

data class ReadListSseDto(
  val readListId: String,
  val bookIds: List<String>,
)
