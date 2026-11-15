package org.lantai.interfaces.api.kobo

import org.assertj.core.api.Assertions.assertThat
import org.lantai.domain.model.LantaiUser
import org.lantai.domain.model.Media
import org.lantai.domain.model.MediaType
import org.lantai.domain.model.UserRoles
import org.lantai.domain.model.makeBook
import org.lantai.domain.model.makeLibrary
import org.lantai.domain.model.makeSeries
import org.lantai.domain.persistence.LantaiUserRepository
import org.lantai.domain.persistence.LibraryRepository
import org.lantai.domain.persistence.MediaRepository
import org.lantai.domain.persistence.SeriesRepository
import org.lantai.domain.service.LantaiUserLifecycle
import org.lantai.domain.service.SeriesLifecycle
import org.lantai.infrastructure.configuration.LantaiSettingsProvider
import org.lantai.infrastructure.kobo.KoboHeaders
import org.lantai.infrastructure.kobo.LantaiSyncTokenGenerator
import org.lantai.infrastructure.web.WebServerEffectiveSettings
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.util.stream.Stream

@SpringBootTest(properties = ["lantai.kobo.sync-item-limit=1"])
@AutoConfigureMockMvc(printOnlyOnFailure = false)
class KoboControllerTest(
  @Autowired private val mockMvc: MockMvc,
  @Autowired private val libraryRepository: LibraryRepository,
  @Autowired private val userRepository: LantaiUserRepository,
  @Autowired private val lantaiUserLifecycle: LantaiUserLifecycle,
  @Autowired private val seriesRepository: SeriesRepository,
  @Autowired private val seriesLifecycle: SeriesLifecycle,
  @Autowired private val mediaRepository: MediaRepository,
  @Autowired private val lantaiSyncTokenGenerator: LantaiSyncTokenGenerator,
  @Autowired private val lantaiSettingsProvider: LantaiSettingsProvider,
  @Autowired private val serverSettings: WebServerEffectiveSettings,
) {
  private val library1 = makeLibrary()
  private val user1 =
    LantaiUser(
      "user@example.org",
      "",
      roles = setOf(UserRoles.KOBO_SYNC),
    )
  private lateinit var apiKey: String

  @BeforeAll
  fun `setup library`() {
    libraryRepository.insert(library1)
    userRepository.insert(user1)
    apiKey = lantaiUserLifecycle.createApiKey(user1, "test")!!.key
  }

  @AfterAll
  fun teardown() {
    libraryRepository.deleteAll()
    lantaiUserLifecycle.deleteUser(user1)
  }

  @AfterEach
  fun `clear repository`() {
    seriesLifecycle.deleteMany(seriesRepository.findAll())
  }

  @Test
  fun `given user when syncing for the first time then books are synced`() {
    // given
    val book1 = makeBook("valid", libraryId = library1.id)
    val book2 = makeBook("valid", libraryId = library1.id)

    makeSeries(name = "series1", libraryId = library1.id).also { series ->
      seriesLifecycle.createSeries(series).let { created ->
        seriesLifecycle.addBooks(created, listOf(book1, book2))
      }
    }

    mediaRepository.findById(book1.id).let { media -> mediaRepository.update(media.copy(status = Media.Status.READY, mediaType = MediaType.EPUB.type)) }
    mediaRepository.findById(book2.id).let { media -> mediaRepository.update(media.copy(status = Media.Status.READY, mediaType = MediaType.EPUB.type)) }

    // first sync
    val mvcResult1 =
      mockMvc
        .get("/kobo/$apiKey/v1/library/sync")
        .andExpect {
          status { isOk() }
          jsonPath("$.length()") { value(1) }
          header { string(KoboHeaders.X_KOBO_SYNC, "continue") }
        }.andReturn()

    val syncToken1Base64 = mvcResult1.response.getHeaderValue(KoboHeaders.X_KOBO_SYNCTOKEN) as String
    val syncToken1 = lantaiSyncTokenGenerator.fromBase64(syncToken1Base64)
    assertThat(syncToken1.ongoingSyncPointId).isNotEmpty()
    assertThat(syncToken1.lastSuccessfulSyncPointId).isNull()

    // second sync
    val mvcResult2 =
      mockMvc
        .get("/kobo/$apiKey/v1/library/sync") {
          header(KoboHeaders.X_KOBO_SYNCTOKEN, syncToken1Base64)
        }.andExpect {
          status { isOk() }
          jsonPath("$.length()") { value(1) }
          header { doesNotExist(KoboHeaders.X_KOBO_SYNC) }
        }.andReturn()

    val syncToken2 = lantaiSyncTokenGenerator.fromBase64(mvcResult2.response.getHeaderValue(KoboHeaders.X_KOBO_SYNCTOKEN) as String)
    assertThat(syncToken2.ongoingSyncPointId).isNull()
    assertThat(syncToken2.lastSuccessfulSyncPointId).isEqualTo(syncToken1.ongoingSyncPointId)
  }

  @Test
  fun `given kobo proxy is enabled when requesting book cover for non-existent book then redirect response is returned`() {
    lantaiSettingsProvider.koboProxy = true

    try {
      mockMvc
        .get("/kobo/$apiKey/v1/books/nonexistent/thumbnail/800/800/false/image.jpg")
        .andExpect {
          status { isTemporaryRedirect() }
          header { string(HttpHeaders.LOCATION, "https://cdn.kobo.com/book-images/nonexistent/800/800/false/image.jpg") }
        }
    } finally {
      lantaiSettingsProvider.koboProxy = false
    }
  }

  @Nested
  inner class HostHeader(
    @param:Value($$"${server.port:#{null}}") private val configServerPort: Int?,
  ) {
    @ParameterizedTest
    @MethodSource("headers")
    fun `given partial host header when getting initialization then img urls are correct`(
      hostHeader: String,
      expected: String,
      extraHeaders: Map<String, String>?,
      koboPort: Int?,
    ) {
      // ServletWebServerInitializedEvent is not triggered during tests
      serverSettings.effectiveServerPort = configServerPort

      val oldPort = lantaiSettingsProvider.koboPort
      koboPort?.let { lantaiSettingsProvider.koboPort = it }

      try {
        mockMvc
          .get("/kobo/$apiKey/v1/initialization") {
            header(HttpHeaders.HOST, hostHeader)
            extraHeaders?.forEach { (h, v) -> header(h, v) }
          }.andExpect {
            jsonPath("Resources.image_host") { value(expected) }
          }.andReturn()
      } finally {
        lantaiSettingsProvider.koboPort = oldPort
      }
    }

    private fun headers(): Stream<Arguments> =
      Stream.of(
        Arguments.of("127.0.0.1", "http://127.0.0.1:$configServerPort", null, null),
        Arguments.of("localhost", "http://localhost:$configServerPort", null, null),
        Arguments.of(
          "127.0.0.1",
          "https://demo.lantai.org",
          mapOf(
            "X-Forwarded-Proto" to "https",
            "X-Forwarded-Host" to "demo.lantai.org",
          ),
          null,
        ),
        Arguments.of("127.0.0.1", "http://127.0.0.1:8085", null, 8085),
        Arguments.of("localhost", "http://localhost:8085", null, 8085),
        Arguments.of(
          "127.0.0.1",
          "https://demo.lantai.org",
          mapOf(
            "X-Forwarded-Proto" to "https",
            "X-Forwarded-Host" to "demo.lantai.org",
          ),
          8085,
        ),
      )
  }
}
