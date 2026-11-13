package org.lantai.application.tasks

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.lantai.domain.persistence.BookRepository
import org.lantai.domain.persistence.LibraryRepository
import org.lantai.domain.persistence.SeriesRepository
import org.lantai.infrastructure.jooq.tasks.LantaiTaskRow
import org.springframework.stereotype.Service

@Service
class TaskLibraryResolver(
  private val objectMapper: ObjectMapper,
  private val bookRepository: BookRepository,
  private val seriesRepository: SeriesRepository,
  private val libraryRepository: LibraryRepository,
) {
  fun resolve(tasks: List<LantaiTaskRow>): Map<String, LibraryRef> {
    if (tasks.isEmpty()) return emptyMap()

    val byTask = mutableMapOf<String, String>()
    val byBook = mutableMapOf<String, MutableList<String>>()
    val bySeries = mutableMapOf<String, MutableList<String>>()

    tasks.forEach { task ->
      val payload = payloadOf(task.payload) ?: return@forEach
      val direct = payload.textOrNull("libraryId")
      if (direct != null) {
        byTask[task.id] = direct
        return@forEach
      }
      payload.textOrNull("bookId")?.let { byBook.getOrPut(it) { mutableListOf() }.add(task.id) }
      payload.textOrNull("seriesId")?.let { bySeries.getOrPut(it) { mutableListOf() }.add(task.id) }
    }

    if (byBook.isNotEmpty()) {
      bookRepository.findAllByIds(byBook.keys).forEach { book ->
        book.libraryId.takeIf { it.isNotBlank() }?.let { libraryId ->
          byBook[book.id]?.forEach { byTask[it] = libraryId }
        }
      }
    }

    if (bySeries.isNotEmpty()) {
      seriesRepository.findAllByIds(bySeries.keys).forEach { series ->
        series.libraryId.takeIf { it.isNotBlank() }?.let { libraryId ->
          bySeries[series.id]?.forEach { byTask[it] = libraryId }
        }
      }
    }

    if (byTask.isEmpty()) return emptyMap()

    val names = libraryRepository.findAllByIds(byTask.values.toSet()).associate { it.id to it.name }
    return byTask
      .mapNotNull { (taskId, libraryId) -> names[libraryId]?.let { taskId to LibraryRef(libraryId, it) } }
      .toMap()
  }

  private fun payloadOf(payload: String): JsonNode? =
    try {
      objectMapper.readTree(payload)?.takeIf { it.isObject }
    } catch (e: JacksonException) {
      null
    }

  private fun JsonNode.textOrNull(field: String): String? =
    get(field)
      ?.takeIf { it.isTextual }
      ?.asText()
      ?.takeIf { it.isNotBlank() }
}

data class LibraryRef(
  val id: String,
  val name: String,
)
