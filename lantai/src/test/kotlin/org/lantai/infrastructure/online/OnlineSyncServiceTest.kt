package org.lantai.infrastructure.online

import org.assertj.core.api.Assertions.assertThat
import org.lantai.domain.persistence.BookMetadataRepository
import org.lantai.domain.persistence.BookRepository
import org.lantai.domain.persistence.MediaRepository
import org.lantai.domain.persistence.OnlineChapterRepository
import org.lantai.domain.persistence.OnlineMangaRepository
import org.lantai.domain.persistence.SeriesRepository
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "LANTAI_ONLINE_SUWAYOMI_PASSWORD", matches = ".+")
class OnlineSyncServiceTest {
  companion object {
    private const val SOURCE_ID = "6353436350537369479"

    private val onlineRoot: Path = Files.createTempDirectory("lantai-online-test")

    @JvmStatic
    @DynamicPropertySource
    fun properties(registry: DynamicPropertyRegistry) {
      registry.add("lantai.online.enabled") { "true" }
      registry.add("lantai.online.root-dir") { onlineRoot.toString() }
      registry.add("lantai.online.suwayomi-endpoint") { System.getenv("LANTAI_ONLINE_SUWAYOMI_ENDPOINT") ?: "http://127.0.0.1:25610" }
      registry.add("lantai.online.suwayomi-username") { System.getenv("LANTAI_ONLINE_SUWAYOMI_USERNAME") ?: "" }
      registry.add("lantai.online.suwayomi-password") { System.getenv("LANTAI_ONLINE_SUWAYOMI_PASSWORD") ?: "" }
    }
  }

  @Autowired private lateinit var syncService: OnlineSyncService

  @Autowired private lateinit var seriesRepository: SeriesRepository

  @Autowired private lateinit var bookRepository: BookRepository

  @Autowired private lateinit var mediaRepository: MediaRepository

  @Autowired private lateinit var bookMetadataRepository: BookMetadataRepository

  @Autowired private lateinit var onlineMangaRepository: OnlineMangaRepository

  @Autowired private lateinit var onlineChapterRepository: OnlineChapterRepository

  @Test
  fun `subscribe sync and unsubscribe`() {
    val candidates = syncService.search("一人之下", SOURCE_ID, 1).mangas
    assumeTrue(candidates.isNotEmpty(), "source returned no candidate")
    val candidate = candidates.first { it.title.contains("一人之下") }

    val result = syncService.subscribe(SOURCE_ID, candidate.id)
    assertThat(result.alreadySubscribed).isFalse()
    assertThat(result.bookCount).isGreaterThan(1)

    val series = seriesRepository.findByIdOrNull(result.seriesId)
    assertThat(series).isNotNull
    assertThat(series!!.bookCount).isEqualTo(result.bookCount)

    val books = bookRepository.findAllBySeriesId(result.seriesId)
    assertThat(books).hasSize(result.bookCount)
    assertThat(mediaRepository.findById(books.first().id).status).isEqualTo(org.lantai.domain.model.Media.Status.READY)
    assertThat(bookMetadataRepository.findById(books.first().id).number).isNotBlank()

    val onlineManga = onlineMangaRepository.findBySeriesIdOrNull(result.seriesId)
    assertThat(onlineManga).isNotNull
    val chapters = onlineChapterRepository.findAllByMangaId(onlineManga!!.id)
    assertThat(chapters).hasSize(result.bookCount)
    assertThat(chapters.map { it.bookId }.toSet()).isEqualTo(books.map { it.id }.toSet())

    val placeholders = series.path.listDirectoryEntries("*.placeholder")
    assertThat(placeholders).hasSize(result.bookCount)
    assertThat(placeholders.sumOf { Files.size(it) }).isZero()

    val again = syncService.subscribe(SOURCE_ID, candidate.id)
    assertThat(again.alreadySubscribed).isTrue()
    assertThat(again.seriesId).isEqualTo(result.seriesId)

    val sync = syncService.sync(result.seriesId)
    assertThat(sync.added).isZero()
    assertThat(sync.total).isEqualTo(result.bookCount)

    syncService.unsubscribe(result.seriesId)
    assertThat(seriesRepository.findByIdOrNull(result.seriesId)).isNull()
    assertThat(bookRepository.findAllBySeriesId(result.seriesId)).isEmpty()
    assertThat(onlineMangaRepository.findBySeriesIdOrNull(result.seriesId)).isNull()
    assertThat(onlineChapterRepository.findAllByMangaId(onlineManga.id)).isEmpty()
    assertThat(series.path.listDirectoryEntries("*.placeholder")).isEmpty()
  }
}
