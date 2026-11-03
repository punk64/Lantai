package org.lantai.interfaces.scheduler

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.MultiGauge
import io.micrometer.core.instrument.MultiGauge.Row
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import org.lantai.domain.model.DomainEvent
import org.lantai.domain.persistence.BookRepository
import org.lantai.domain.persistence.LibraryRepository
import org.lantai.domain.persistence.ReadListRepository
import org.lantai.domain.persistence.SeriesCollectionRepository
import org.lantai.domain.persistence.SeriesRepository
import org.lantai.domain.persistence.SidecarRepository
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong

private const val LIBRARIES = "libraries"
private const val SERIES = "series"
private const val BOOKS = "books"
private const val BOOKS_FILESIZE = "books.filesize"
private const val COLLECTIONS = "collections"
private const val READLISTS = "readlists"
private const val SIDECARS = "sidecars"

const val METER_TASKS_EXECUTION = "lantai.tasks.execution"
const val METER_TASKS_FAILURE = "lantai.tasks.failure"

@Profile("!test")
@Component
class MetricsPublisherController(
  private val libraryRepository: LibraryRepository,
  private val bookRepository: BookRepository,
  private val seriesRepository: SeriesRepository,
  private val collectionRepository: SeriesCollectionRepository,
  private val readListRepository: ReadListRepository,
  private val sidecarRepository: SidecarRepository,
  private val meterRegistry: MeterRegistry,
) {
  init {
    Timer
      .builder(METER_TASKS_EXECUTION)
      .description("Task execution time")
      .register(meterRegistry)

    Counter
      .builder(METER_TASKS_FAILURE)
      .description("Count of failed tasks")
      .register(meterRegistry)
  }

  private final val entitiesMultiTag = listOf(SERIES, BOOKS, BOOKS_FILESIZE, SIDECARS)
  private final val entitiesNoTags = listOf(LIBRARIES, COLLECTIONS, READLISTS)
  private final val allEntities = entitiesMultiTag + entitiesNoTags

  val multiGauges =
    entitiesMultiTag.associateWith { entity ->
      MultiGauge
        .builder("lantai.$entity")
        .description("The number of $entity")
        .baseUnit("count")
        .register(meterRegistry)
    }

  val noTagGauges =
    entitiesNoTags.associateWith { entity ->
      AtomicLong(0).also { value ->
        Gauge
          .builder("lantai.$entity", value) { value.get().toDouble() }
          .description("The number of $entity")
          .baseUnit("count")
          .register(meterRegistry)
      }
    }

  val bookFileSizeGauge =
    MultiGauge
      .builder("lantai.$BOOKS_FILESIZE")
      .description("The cumulated filesize of books")
      .baseUnit("bytes")
      .register(meterRegistry)

  @EventListener
  private fun pushMetricsOnEvent(event: DomainEvent) {
    when (event) {
      is DomainEvent.LibraryScanned -> entitiesMultiTag.forEach { pushMetricsCount(it) }

      is DomainEvent.LibraryAdded -> noTagGauges[LIBRARIES]?.incrementAndGet()
      is DomainEvent.LibraryDeleted -> {
        noTagGauges[LIBRARIES]?.decrementAndGet()
        entitiesMultiTag.forEach { pushMetricsCount(it) }
      }
      is DomainEvent.CollectionAdded -> noTagGauges[COLLECTIONS]?.incrementAndGet()
      is DomainEvent.CollectionDeleted -> noTagGauges[COLLECTIONS]?.decrementAndGet()
      is DomainEvent.ReadListAdded -> noTagGauges[READLISTS]?.incrementAndGet()
      is DomainEvent.ReadListDeleted -> noTagGauges[READLISTS]?.decrementAndGet()

      else -> Unit
    }
  }

  @EventListener(ApplicationReadyEvent::class)
  fun pushAllMetrics() {
    allEntities.forEach { pushMetricsCount(it) }
  }

  private fun pushMetricsCount(entity: String) {
    when (entity) {
      LIBRARIES -> noTagGauges[LIBRARIES]?.set(libraryRepository.count())
      COLLECTIONS -> noTagGauges[COLLECTIONS]?.set(collectionRepository.count())
      READLISTS -> noTagGauges[READLISTS]?.set(readListRepository.count())

      SERIES -> multiGauges[SERIES]?.register(seriesRepository.countGroupedByLibraryId().map { Row.of(Tags.of("library", it.key), it.value) }, true)
      BOOKS -> multiGauges[BOOKS]?.register(bookRepository.countGroupedByLibraryId().map { Row.of(Tags.of("library", it.key), it.value) }, true)
      BOOKS_FILESIZE -> bookFileSizeGauge.register(bookRepository.getFilesizeGroupedByLibraryId().map { Row.of(Tags.of("library", it.key), it.value) }, true)
      SIDECARS -> multiGauges[SIDECARS]?.register(sidecarRepository.countGroupedByLibraryId().map { Row.of(Tags.of("library", it.key), it.value) }, true)
    }
  }
}
