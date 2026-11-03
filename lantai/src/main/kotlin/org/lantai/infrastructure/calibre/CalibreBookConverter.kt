package org.lantai.infrastructure.calibre

import io.github.oshai.kotlinlogging.KotlinLogging
import org.lantai.application.query.UnpagedSorted
import org.lantai.application.tasks.TaskEmitter
import org.lantai.domain.model.SearchCondition
import org.lantai.domain.model.SearchContext
import org.lantai.domain.model.SearchOperator
import org.lantai.domain.model.isConverted
import org.lantai.domain.model.isConvertible
import org.lantai.domain.persistence.BookRepository
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

data class CalibreBookConversion(
  val bookId: String,
  val target: String,
  val queuedAnalysis: Boolean,
)

data class CalibreBatchConversion(
  val requested: Int,
  val converted: List<String>,
  val failed: Map<String, String>,
  val skipped: Int,
)

@Service
class CalibreBookConverter(
  private val converter: CalibreConverter,
  private val runtime: CalibreRuntime,
  private val bookRepository: BookRepository,
  private val properties: CalibreProperties,
  private val taskEmitter: TaskEmitter,
) {
  fun convertBook(bookId: String): CalibreBookConversion {
    if (!runtime.isAvailable()) throw CalibreException("Calibre is not available")
    val book =
      bookRepository.findByIdOrNull(bookId)
        ?: throw CalibreException("Book $bookId does not exist")
    if (!book.isConvertible()) throw CalibreException("Book $bookId is not a Calibre-convertible format")
    val target = converter.convert(book.path)
    taskEmitter.analyzeBook(book)
    logger.info { "Queued analysis for converted book $bookId" }
    return CalibreBookConversion(bookId = bookId, target = target.toString(), queuedAnalysis = true)
  }

  fun convertLibrary(
    libraryId: String,
    limit: Int? = null,
  ): CalibreBatchConversion {
    if (!runtime.isAvailable()) throw CalibreException("Calibre is not available")
    val pending = pendingBooks(libraryId)
    val effectiveLimit = (limit ?: properties.batchLimit).coerceAtLeast(1)
    val selected = pending.take(effectiveLimit)
    val converted = mutableListOf<String>()
    val failed = linkedMapOf<String, String>()
    selected.forEach { book ->
      try {
        converter.convert(book.path)
        converted.add(book.id)
      } catch (e: Exception) {
        logger.warn(e) { "Failed to convert book ${book.id}" }
        failed[book.id] = e.message ?: "conversion failed"
      }
    }
    if (converted.isNotEmpty()) {
      taskEmitter.analyzeBook(selected.filter { it.id in converted })
    }
    return CalibreBatchConversion(
      requested = pending.size,
      converted = converted,
      failed = failed,
      skipped = pending.size - selected.size,
    )
  }

  fun pendingCount(libraryId: String): Int = pendingBooks(libraryId).size

  private fun pendingBooks(libraryId: String) =
    bookRepository
      .findAll(
        SearchCondition.LibraryId(SearchOperator.Is(libraryId)),
        SearchContext.empty(),
        UnpagedSorted(Sort.by(Sort.Order.asc("seriesId"), Sort.Order.asc("number"))),
      ).content
      .filter { it.isConvertible() && !it.isConverted() }
}
