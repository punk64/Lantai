package org.lantai.application.tasks

import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import org.lantai.domain.model.BookAction
import org.lantai.domain.model.Book
import org.lantai.domain.persistence.BookRepository
import org.lantai.domain.persistence.LibraryRepository
import org.lantai.domain.persistence.SeriesRepository
import org.lantai.domain.service.BookConverter
import org.lantai.domain.service.BookImporter
import org.lantai.domain.service.BookLifecycle
import org.lantai.domain.service.BookMetadataLifecycle
import org.lantai.domain.service.BookPageEditor
import org.lantai.domain.service.LibraryContentLifecycle
import org.lantai.domain.service.LocalArtworkLifecycle
import org.lantai.domain.service.PageHashLifecycle
import org.lantai.domain.service.SeriesLifecycle
import org.lantai.domain.service.SeriesMetadataLifecycle
import org.lantai.infrastructure.search.SearchIndexLifecycle
import org.lantai.interfaces.scheduler.METER_TASKS_EXECUTION
import org.lantai.interfaces.scheduler.METER_TASKS_FAILURE
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.time.measureTime
import kotlin.time.toJavaDuration

private val logger = KotlinLogging.logger {}

@Service
class TaskHandler(
  private val taskEmitter: TaskEmitter,
  private val libraryRepository: LibraryRepository,
  private val bookRepository: BookRepository,
  private val seriesRepository: SeriesRepository,
  private val libraryContentLifecycle: LibraryContentLifecycle,
  private val bookLifecycle: BookLifecycle,
  private val bookMetadataLifecycle: BookMetadataLifecycle,
  private val seriesLifecycle: SeriesLifecycle,
  private val seriesMetadataLifecycle: SeriesMetadataLifecycle,
  private val localArtworkLifecycle: LocalArtworkLifecycle,
  private val bookImporter: BookImporter,
  private val bookConverter: BookConverter,
  private val bookPageEditor: BookPageEditor,
  private val searchIndexLifecycle: SearchIndexLifecycle,
  private val pageHashLifecycle: PageHashLifecycle,
  private val meterRegistry: MeterRegistry,
) {
  fun handleTask(task: Task) {
    logger.info { "Executing task: $task" }
    try {
      measureTime {
        when (task) {
          is Task.ScanLibrary ->
            libraryRepository.findByIdOrNull(task.libraryId)?.let { library ->
              libraryContentLifecycle.scanRootFolder(library, task.scanDeep)
              taskEmitter.analyzeUnknownAndOutdatedBooks(library)
              taskEmitter.repairExtensions(library, LOW_PRIORITY)
              taskEmitter.findBooksToConvert(library, LOWEST_PRIORITY)
              taskEmitter.findBooksWithMissingPageHash(library, LOWEST_PRIORITY)
              taskEmitter.findDuplicatePagesToDelete(library, LOWEST_PRIORITY)
              taskEmitter.hashBooksWithoutHash(library)
              taskEmitter.hashBooksWithoutHashKoreader(library)
            } ?: logger.warn { "Cannot execute task $task: Library does not exist" }

          is Task.FindBooksToConvert ->
            libraryRepository.findByIdOrNull(task.libraryId)?.let { library ->
              taskEmitter.convertBookToCbz(bookConverter.getConvertibleBooks(library), task.priority + 1)
            } ?: logger.warn { "Cannot execute task $task: Library does not exist" }

          is Task.FindBooksWithMissingPageHash ->
            libraryRepository.findByIdOrNull(task.libraryId)?.let { library ->
              taskEmitter.hashBookPages(pageHashLifecycle.getBookIdsWithMissingPageHash(library), task.priority + 1)
            } ?: logger.warn { "Cannot execute task $task: Library does not exist" }

          is Task.FindDuplicatePagesToDelete ->
            libraryRepository.findByIdOrNull(task.libraryId)?.let { library ->
              taskEmitter.removeDuplicatePages(pageHashLifecycle.getBookPagesToDeleteAutomatically(library), task.priority + 1)
            } ?: logger.warn { "Cannot execute task $task: Library does not exist" }

          is Task.EmptyTrash ->
            libraryRepository.findByIdOrNull(task.libraryId)?.let { library ->
              libraryContentLifecycle.emptyTrash(library)
            } ?: logger.warn { "Cannot execute task $task: Library does not exist" }

          is Task.AnalyzeBook -> {
            val book = bookRepository.findByIdOrNull(task.bookId)?.takeIf { ensureBookFile(it) }
            try {
              if (book == null) {
                logger.warn { "Cannot execute task $task: Book does not exist" }
              } else {
                val actions = bookLifecycle.analyzeAndPersist(book)
                if (actions.contains(BookAction.GENERATE_THUMBNAIL)) taskEmitter.generateBookThumbnail(book.id, priority = task.priority + 1)
                if (actions.contains(BookAction.REFRESH_METADATA)) taskEmitter.refreshBookMetadata(book, priority = task.priority + 1)
              }
            } catch (e: Exception) {
              if (book != null) bookLifecycle.markAnalysisFailed(book)
              throw e
            } finally {
              runCatching { taskEmitter.analyzeNextBook(book?.libraryId ?: task.libraryId, task.priority) }
            }
          }

          is Task.GenerateBookThumbnail ->
            bookRepository.findByIdOrNull(task.bookId)?.takeIf { ensureBookFile(it) }?.let { book ->
              bookLifecycle.generateThumbnailAndPersist(book)
            } ?: logger.warn { "Cannot execute task $task: Book does not exist" }

          is Task.RefreshBookMetadata ->
            bookRepository.findByIdOrNull(task.bookId)?.takeIf { ensureBookFile(it) }?.let { book ->
              bookMetadataLifecycle.refreshMetadata(book, task.capabilities)
              taskEmitter.refreshSeriesMetadata(book.seriesId, priority = task.priority - 1)
            } ?: logger.warn { "Cannot execute task $task: Book does not exist" }

          is Task.RefreshSeriesMetadata ->
            seriesRepository.findByIdOrNull(task.seriesId)?.let { series ->
              seriesMetadataLifecycle.refreshMetadata(series)
              taskEmitter.aggregateSeriesMetadata(series.id, priority = task.priority)
            } ?: logger.warn { "Cannot execute task $task: Series does not exist" }

          is Task.AggregateSeriesMetadata ->
            seriesRepository.findByIdOrNull(task.seriesId)?.let { series ->
              seriesMetadataLifecycle.aggregateMetadata(series)
            } ?: logger.warn { "Cannot execute task $task: Series does not exist" }

          is Task.RefreshBookLocalArtwork ->
            bookRepository.findByIdOrNull(task.bookId)?.let { book ->
              localArtworkLifecycle.refreshLocalArtwork(book)
            } ?: logger.warn { "Cannot execute task $task: Book does not exist" }

          is Task.RefreshSeriesLocalArtwork ->
            seriesRepository.findByIdOrNull(task.seriesId)?.let { series ->
              localArtworkLifecycle.refreshLocalArtwork(series)
            } ?: logger.warn { "Cannot execute task $task: Series does not exist" }

          is Task.ImportBook ->
            seriesRepository.findByIdOrNull(task.seriesId)?.let { series ->
              val importedBook = bookImporter.importBook(Paths.get(task.sourceFile), series, task.copyMode, task.destinationName, task.upgradeBookId)
              taskEmitter.analyzeBook(importedBook, priority = task.priority + 1)
            } ?: logger.warn { "Cannot execute task $task: Series does not exist" }

          is Task.ConvertBook ->
            bookRepository.findByIdOrNull(task.bookId)?.let { book ->
              bookConverter.convertToCbz(book)
            } ?: logger.warn { "Cannot execute task $task: Book does not exist" }

          is Task.RepairExtension ->
            bookRepository.findByIdOrNull(task.bookId)?.let { book ->
              bookConverter.repairExtension(book)
            } ?: logger.warn { "Cannot execute task $task: Book does not exist" }

          is Task.RemoveHashedPages ->
            bookRepository.findByIdOrNull(task.bookId)?.let { book ->
              if (bookPageEditor.removeHashedPages(book, task.pages) == BookAction.GENERATE_THUMBNAIL) {
                taskEmitter.generateBookThumbnail(book.id, priority = task.priority + 1)
              }
            } ?: logger.warn { "Cannot execute task $task: Book does not exist" }

          is Task.HashBook ->
            bookRepository.findByIdOrNull(task.bookId)?.let { book ->
              bookLifecycle.hashAndPersist(book)
            } ?: logger.warn { "Cannot execute task $task: Book does not exist" }

          is Task.HashBookKoreader ->
            bookRepository.findByIdOrNull(task.bookId)?.let { book ->
              bookLifecycle.hashKoreaderAndPersist(book)
            } ?: logger.warn { "Cannot execute task $task: Book does not exist" }

          is Task.HashBookPages ->
            bookRepository.findByIdOrNull(task.bookId)?.let { book ->
              bookLifecycle.hashPagesAndPersist(book)
            } ?: logger.warn { "Cannot execute task $task: Book does not exist" }

          is Task.RebuildIndex -> searchIndexLifecycle.rebuildIndex(task.entities)

          is Task.UpgradeIndex -> searchIndexLifecycle.upgradeIndex()

          is Task.DeleteBook -> {
            bookRepository.findByIdOrNull(task.bookId)?.let { book ->
              if (book.oneshot)
                seriesLifecycle.deleteSeriesFiles(seriesRepository.findByIdOrNull(book.seriesId)!!)
              else
                bookLifecycle.deleteBookFiles(book)
            }
          }

          is Task.DeleteSeries -> {
            seriesRepository.findByIdOrNull(task.seriesId)?.let { series ->
              seriesLifecycle.deleteSeriesFiles(series)
            }
          }

          is Task.FindBookThumbnailsToRegenerate -> {
            taskEmitter.generateBookThumbnail(bookLifecycle.findBookThumbnailsToRegenerate(task.forBiggerResultOnly), task.priority)
          }
        }
      }.also {
        logger.info { "Task $task executed in $it" }
        meterRegistry.timer(METER_TASKS_EXECUTION, "type", task.javaClass.simpleName).record(it.toJavaDuration())
      }
    } catch (e: Exception) {
      logger.error(e) { "Task $task execution failed" }
      meterRegistry.counter(METER_TASKS_FAILURE, "type", task.javaClass.simpleName).increment()
      throw e
    }
  }

  private fun ensureBookFile(book: Book): Boolean {
    if (Files.exists(book.path)) return true
    logger.warn { "Book file is missing on disk, soft deleting and skipping: $book" }
    bookLifecycle.softDeleteMany(listOf(book))
    return false
  }
}
