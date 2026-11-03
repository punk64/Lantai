package org.lantai.infrastructure.online

import org.assertj.core.api.Assertions.assertThat
import org.lantai.domain.persistence.BookRepository
import org.lantai.domain.persistence.MediaRepository
import org.lantai.domain.persistence.OnlineChapterRepository
import org.lantai.domain.persistence.OnlineMangaRepository
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Files
import java.nio.file.Path

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "LANTAI_ONLINE_SUWAYOMI_PASSWORD", matches = ".+")
class OnlineDownloadServiceTest {
  companion object {
    private const val SOURCE_ID = "6353436350537369479"

    private val onlineRoot: Path = Files.createTempDirectory("lantai-online-download-test")

    @JvmStatic
    @DynamicPropertySource
    fun properties(registry: DynamicPropertyRegistry) {
      registry.add("lantai.online.enabled") { "true" }
      registry.add("lantai.online.root-dir") { onlineRoot.toString() }
      registry.add("lantai.online.suwayomi-endpoint") { System.getenv("LANTAI_ONLINE_SUWAYOMI_ENDPOINT") ?: "http://127.0.0.1:25610" }
      registry.add("lantai.online.suwayomi-username") { System.getenv("LANTAI_ONLINE_SUWAYOMI_USERNAME") ?: "" }
      registry.add("lantai.online.suwayomi-password") { System.getenv("LANTAI_ONLINE_SUWAYOMI_PASSWORD") ?: "" }
      registry.add("lantai.online.suwayomi-downloads-dir") { System.getenv("LANTAI_ONLINE_SUWAYOMI_DOWNLOADS_DIR") ?: "" }
    }
  }

  @Autowired private lateinit var syncService: OnlineSyncService

  @Autowired private lateinit var downloadService: OnlineDownloadService

  @Autowired private lateinit var readService: OnlineReadService

  @Autowired private lateinit var onlineMangaRepository: OnlineMangaRepository

  @Autowired private lateinit var onlineChapterRepository: OnlineChapterRepository

  @Autowired private lateinit var bookRepository: BookRepository

  @Autowired private lateinit var mediaRepository: MediaRepository

  @Test
  fun `download chapter as cbz and re-analyze`() {
    val candidates = syncService.search("一人之下", SOURCE_ID, 1).mangas
    assumeTrue(candidates.isNotEmpty(), "source returned no candidate")
    val candidate = candidates.first { it.title.contains("一人之下") }

    val subscribed = syncService.subscribe(SOURCE_ID, candidate.id)
    try {
      val manga = onlineMangaRepository.findBySeriesIdOrNull(subscribed.seriesId)!!
      val pending = onlineChapterRepository.findAllByMangaId(manga.id).first { !it.downloaded && it.remoteId > 0 }
      val before = bookRepository.findByIdOrNull(pending.bookId)!!
      assertThat(before.path.fileName.toString()).endsWith(".placeholder")

      val result = downloadService.download(pending.bookId)
      assertThat(result.alreadyDownloaded).isFalse()
      assertThat(result.pageCount).isGreaterThan(0)
      assertThat(result.sizeBytes).isGreaterThan(1024)

      val after = bookRepository.findByIdOrNull(pending.bookId)!!
      assertThat(after.path.fileName.toString()).endsWith(".cbz")
      assertThat(after.fileSize).isEqualTo(result.sizeBytes)
      assertThat(Files.exists(before.path)).isFalse()
      assertThat(onlineChapterRepository.findByBookIdOrNull(pending.bookId)!!.downloaded).isTrue()

      val media = mediaRepository.findById(pending.bookId)
      assertThat(media.pageCount).isEqualTo(result.pageCount)
      assertThat(media.pages).hasSize(media.pageCount)
      assertThat(readService.pageOrNull(pending.bookId, 1)).isNull()

      val again = downloadService.download(pending.bookId)
      assertThat(again.alreadyDownloaded).isTrue()
      assertThat(again.pageCount).isEqualTo(result.pageCount)
    } finally {
      syncService.unsubscribe(subscribed.seriesId)
    }
  }
}
