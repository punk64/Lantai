package org.lantai.infrastructure.online

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.lantai.domain.model.LantaiUser
import org.lantai.domain.model.UserRoles
import org.lantai.domain.persistence.MediaRepository
import org.lantai.domain.persistence.OnlineChapterRepository
import org.lantai.domain.persistence.OnlineMangaRepository
import org.lantai.domain.persistence.ThumbnailBookRepository
import org.lantai.domain.persistence.ThumbnailSeriesRepository
import org.lantai.infrastructure.security.LantaiPrincipal
import org.lantai.interfaces.api.CommonBookController
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.context.request.ServletWebRequest
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Files
import java.nio.file.Path

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "LANTAI_ONLINE_SUWAYOMI_PASSWORD", matches = ".+")
class OnlineReadServiceTest {
  companion object {
    private const val SOURCE_ID = "6353436350537369479"

    private val onlineRoot: Path = Files.createTempDirectory("lantai-online-read-test")

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

  @Autowired private lateinit var readService: OnlineReadService

  @Autowired private lateinit var mediaRepository: MediaRepository

  @Autowired private lateinit var onlineMangaRepository: OnlineMangaRepository

  @Autowired private lateinit var onlineChapterRepository: OnlineChapterRepository

  @Autowired private lateinit var thumbnailSeriesRepository: ThumbnailSeriesRepository

  @Autowired private lateinit var thumbnailBookRepository: ThumbnailBookRepository

  @Autowired private lateinit var commonBookController: CommonBookController

  @Test
  fun `lazy load pages and series cover`() {
    val candidates = syncService.search("一人之下", SOURCE_ID, 1).mangas
    assumeTrue(candidates.isNotEmpty(), "source returned no candidate")
    val candidate = candidates.first { it.title.contains("一人之下") }

    val subscribed = syncService.subscribe(SOURCE_ID, candidate.id)
    try {
      val onlineManga = onlineMangaRepository.findBySeriesIdOrNull(subscribed.seriesId)
      assertThat(onlineManga).isNotNull
      val chapters = onlineChapterRepository.findAllByMangaId(onlineManga!!.id)
      assertThat(chapters).allMatch { it.remoteId > 0 }

      val cover = thumbnailSeriesRepository.findSelectedBySeriesIdOrNull(subscribed.seriesId)
      assertThat(cover).isNotNull
      assertThat(cover!!.fileSize).isGreaterThan(0)
      assertThat(cover.dimension.width).isGreaterThan(0)

      val pending = chapters.first { it.pageCount < 0 && !it.downloaded }
      assertThat(mediaRepository.findById(pending.bookId).pageCount).isZero()

      readService.ensurePages(pending.bookId)

      val media = mediaRepository.findById(pending.bookId)
      assertThat(media.pageCount).isGreaterThan(0)
      assertThat(media.pages).hasSize(media.pageCount)
      assertThat(media.pages.first().mediaType).isEqualTo("image/jpeg")
      assertThat(onlineChapterRepository.findByBookIdOrNull(pending.bookId)!!.pageCount).isEqualTo(media.pageCount)

      readService.ensurePages(pending.bookId)
      assertThat(mediaRepository.findById(pending.bookId).pageCount).isEqualTo(media.pageCount)
      assertThat(onlineChapterRepository.findByBookIdOrNull(pending.bookId)!!.pageCount).isEqualTo(media.pageCount)

      val firstPage = readService.pageOrNull(pending.bookId, 1)
      assertThat(firstPage).isNotNull
      assertThat(firstPage!!.bytes.size).isGreaterThan(1024)
      assertThat(firstPage.mediaType).startsWith("image/")

      readService.ensureBookThumbnail(pending.bookId)
      readService.ensureBookThumbnail(pending.bookId)
      val thumbnail = thumbnailBookRepository.findSelectedByBookIdOrNull(pending.bookId)
      assertThat(thumbnail).isNotNull
      assertThat(thumbnail!!.fileSize).isGreaterThan(0)
      assertThat(thumbnail.dimension.width).isGreaterThan(0)

      val user = LantaiUser(email = "online-page@lantai.local", password = "x", roles = setOf(UserRoles.ADMIN))
      val response =
        commonBookController.getBookPageInternal(
          bookId = pending.bookId,
          pageNumber = 1,
          convertTo = null,
          request = ServletWebRequest(MockHttpServletRequest()),
          principal = LantaiPrincipal(user),
          acceptHeaders = null,
        )
      assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
      assertThat(response.headers.contentType.toString()).startsWith("image/")
      assertThat(response.body).isEqualTo(firstPage.bytes)

      assertThatThrownBy {
        commonBookController.getBookPageInternal(
          bookId = pending.bookId,
          pageNumber = media.pageCount + 1,
          convertTo = null,
          request = ServletWebRequest(MockHttpServletRequest()),
          principal = LantaiPrincipal(user),
          acceptHeaders = null,
        )
      }.isInstanceOf(ResponseStatusException::class.java)
        .satisfies({ assertThat((it as ResponseStatusException).statusCode).isEqualTo(HttpStatus.BAD_REQUEST) })
    } finally {
      syncService.unsubscribe(subscribed.seriesId)
    }
  }
}
