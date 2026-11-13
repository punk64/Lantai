package org.lantai.infrastructure.metadata.scrape

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.lantai.domain.model.Book
import org.lantai.domain.model.ScrapeQueueItem
import org.lantai.domain.model.Series
import org.lantai.domain.persistence.BookRepository
import org.lantai.domain.persistence.ScrapeInboxRepository
import org.lantai.domain.persistence.ScrapeQueueRepository
import org.lantai.domain.persistence.SeriesMetadataRepository
import org.lantai.domain.persistence.SeriesReadiness
import org.lantai.domain.persistence.SeriesRepository
import org.springframework.context.ApplicationEventPublisher

class ScrapeQueueReadinessTest {
  private val queueRepository = mockk<ScrapeQueueRepository>(relaxed = true)
  private val seriesRepository = mockk<SeriesRepository>(relaxed = true)
  private val seriesMetadataRepository = mockk<SeriesMetadataRepository>(relaxed = true)
  private val bookRepository = mockk<BookRepository>(relaxed = true)
  private val bookCatalog = mockk<ScrapeBookCatalog>(relaxed = true)
  private val engine = mockk<ScrapeEngine>(relaxed = true)
  private val applier = mockk<ScrapeApplier>(relaxed = true)
  private val inboxRepository = mockk<ScrapeInboxRepository>(relaxed = true)
  private val settings = mockk<ScrapeSettingsProvider>(relaxed = true)
  private val events = mockk<ApplicationEventPublisher>(relaxed = true)
  private val service =
    ScrapeQueueService(
      queueRepository,
      seriesRepository,
      seriesMetadataRepository,
      bookRepository,
      bookCatalog,
      engine,
      applier,
      inboxRepository,
      settings,
      events,
      ObjectMapper(),
    )

  private fun seriesOf(seriesId: String): Series =
    mockk {
      every { id } returns seriesId
      every { name } returns "S-$seriesId"
      every { libraryId } returns "lib"
    }

  private fun bookOf(bookId: String, seriesId: String): Book =
    mockk {
      every { id } returns bookId
      every { this@mockk.seriesId } returns seriesId
      every { name } returns "$bookId.cbz"
      every { number } returns 1
    }

  private fun stubSeries(vararg ids: String) {
    ids.forEach { id ->
      every { seriesRepository.findByIdOrNull(id) } returns seriesOf(id)
      every { seriesMetadataRepository.findByIdOrNull(id) } returns null
      every { queueRepository.findBySeriesId(id) } returns null
    }
  }

  @Test
  fun `skips series whose books are all unparsed`() {
    stubSeries("s1")
    every { bookRepository.findReadinessBySeries(listOf("s1")) } returns
      mapOf("s1" to SeriesReadiness(total = 6, readyBookIds = emptyList()))

    val result = service.enqueueSeries(listOf("s1"), ScrapeQueueItem.Origin.SERIES)

    assertThat(result.added).isZero()
    assertThat(result.notReady).isEqualTo(1)
  }

  @Test
  fun `narrows scope to parsed books when the series is partially ready`() {
    stubSeries("s1")
    every { bookRepository.findReadinessBySeries(listOf("s1")) } returns
      mapOf("s1" to SeriesReadiness(total = 3, readyBookIds = listOf("b1", "b3")))
    val captured = slot<ScrapeQueueItem>()
    every { queueRepository.insert(capture(captured)) } returns Unit

    val result = service.enqueueSeries(listOf("s1"), ScrapeQueueItem.Origin.SERIES)

    assertThat(result.added).isEqualTo(1)
    assertThat(result.notReady).isZero()
    assertThat(captured.captured.bookIds).containsExactly("b1", "b3")
  }

  @Test
  fun `keeps whole-series scope when every book is parsed`() {
    stubSeries("s1")
    every { bookRepository.findReadinessBySeries(listOf("s1")) } returns
      mapOf("s1" to SeriesReadiness(total = 2, readyBookIds = listOf("b1", "b2")))
    val captured = slot<ScrapeQueueItem>()
    every { queueRepository.insert(capture(captured)) } returns Unit

    service.enqueueSeries(listOf("s1"), ScrapeQueueItem.Origin.SERIES)

    assertThat(captured.captured.bookIds).isEmpty()
  }

  @Test
  fun `drops unparsed books from an explicit book selection`() {
    stubSeries("s1")
    val books = listOf(bookOf("b1", "s1"), bookOf("b2", "s1"), bookOf("b3", "s1"))
    every { bookRepository.findAllByIds(listOf("b1", "b2", "b3")) } returns books
    every { bookRepository.findReadinessBySeries(listOf("s1", "s1", "s1")) } returns
      mapOf("s1" to SeriesReadiness(total = 3, readyBookIds = listOf("b2")))
    val captured = slot<ScrapeQueueItem>()
    every { queueRepository.insert(capture(captured)) } returns Unit

    val result = service.enqueueBooks(listOf("b1", "b2", "b3"), ScrapeQueueItem.Origin.MULTI)

    assertThat(result.added).isEqualTo(1)
    assertThat(result.notReady).isEqualTo(2)
    assertThat(captured.captured.bookIds).containsExactly("b2")
  }
}
