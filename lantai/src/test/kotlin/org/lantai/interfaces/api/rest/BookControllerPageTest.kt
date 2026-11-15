package org.lantai.interfaces.api.rest

import com.ninjasquad.springmockk.MockkBean
import com.ninjasquad.springmockk.SpykBean
import io.mockk.every
import org.lantai.domain.model.BookPage
import org.lantai.domain.model.Media
import org.lantai.domain.model.TypedBytes
import org.lantai.domain.model.makeBook
import org.lantai.domain.model.makeLibrary
import org.lantai.domain.model.makeSeries
import org.lantai.domain.persistence.BookRepository
import org.lantai.domain.persistence.LantaiUserRepository
import org.lantai.domain.persistence.LibraryRepository
import org.lantai.domain.persistence.MediaRepository
import org.lantai.domain.persistence.SeriesRepository
import org.lantai.domain.service.BookAnalyzer
import org.lantai.domain.service.BookLifecycle
import org.lantai.domain.service.LantaiUserLifecycle
import org.lantai.domain.service.LibraryLifecycle
import org.lantai.domain.service.SeriesLifecycle
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.util.stream.Stream

@SpringBootTest
@AutoConfigureMockMvc(printOnlyOnFailure = false)
class BookControllerPageTest(
  @Autowired private val seriesRepository: SeriesRepository,
  @Autowired private val seriesLifecycle: SeriesLifecycle,
  @Autowired private val mediaRepository: MediaRepository,
  @Autowired private val libraryRepository: LibraryRepository,
  @Autowired private val libraryLifecycle: LibraryLifecycle,
  @Autowired private val bookRepository: BookRepository,
  @Autowired private val userRepository: LantaiUserRepository,
  @Autowired private val userLifecycle: LantaiUserLifecycle,
  @Autowired private val mockMvc: MockMvc,
) {
  @MockkBean
  private lateinit var mockAnalyzer: BookAnalyzer

  @SpykBean
  private lateinit var bookLifecycle: BookLifecycle

  private val library = makeLibrary(id = "1")

  @BeforeAll
  fun `setup library`() {
    libraryRepository.insert(library)
  }

  @AfterAll
  fun teardown() {
    libraryRepository.findAll().forEach {
      libraryLifecycle.deleteLibrary(it)
    }
  }

  @AfterEach
  fun `clear repository`() {
    seriesLifecycle.deleteMany(seriesRepository.findAll())
  }

  @ParameterizedTest
  @MethodSource("arguments")
  @WithMockCustomUser
  fun `given pdf book when getting page with Accept header then returns page in correct format`(
    bookType: String,
    acceptTypes: List<MediaType>,
    success: Boolean,
    resultType: String?,
  ) {
    makeSeries(name = "series", libraryId = library.id).let { series ->
      seriesLifecycle.createSeries(series).let { created ->
        val books = listOf(makeBook("1", libraryId = library.id))
        seriesLifecycle.addBooks(created, books)
      }
    }

    val book = bookRepository.findAll().first()
    mediaRepository.findById(book.id).let {
      mediaRepository.update(
        it.copy(
          status = Media.Status.READY,
          mediaType = bookType,
          pages = listOf(BookPage("file", "image/jpeg")),
        ),
      )
    }

    every { mockAnalyzer.getPageContentRaw(any(), 1) } returns TypedBytes(ByteArray(0), "application/pdf")
    every { bookLifecycle.getBookPage(any(), 1, any(), any()) } returns TypedBytes(ByteArray(0), "image/jpeg")

    mockMvc
      .get("/api/v1/books/${book.id}/pages/1") {
        if (acceptTypes.isNotEmpty()) accept(*acceptTypes.toTypedArray())
      }.andExpect {
        status { if (success) isOk() else isBadRequest() }
        if (resultType != null)
          header { string(HttpHeaders.CONTENT_TYPE, resultType) }
      }
  }

  fun arguments(): Stream<Arguments> =
    Stream.of(
      // PDF book: request nothing, get image
      Arguments.of(
        "application/pdf",
        emptyList<MediaType>(),
        true,
        "image/jpeg",
      ),
      // PDF book: request PDF, get PDF
      Arguments.of(
        "application/pdf",
        listOf(MediaType.APPLICATION_PDF),
        true,
        "application/pdf",
      ),
      // PDF book: request PDF + others, get PDF
      Arguments.of(
        "application/pdf",
        listOf(MediaType.APPLICATION_PDF, MediaType("image")),
        true,
        "application/pdf",
      ),
      // PDF book: request image, get image
      Arguments.of(
        "application/pdf",
        listOf(MediaType("image")),
        true,
        "image/jpeg",
      ),
      // PDF book: request unhandled image, still get image. We don't check image subtypes.
      Arguments.of(
        "application/pdf",
        listOf(MediaType("image", "avif")),
        true,
        "image/jpeg",
      ),
      // PDF book: request random, get image. We ignore non-pdf types.
      Arguments.of(
        "application/pdf",
        listOf(MediaType.APPLICATION_ATOM_XML),
        true,
        "image/jpeg",
      ),
      // PDF book: request PDF with lower priority than image, get image
      Arguments.of(
        "application/pdf",
        listOf(MediaType.IMAGE_JPEG, MediaType.APPLICATION_PDF),
        true,
        "image/jpeg",
      ),
      // PDF book: request PDF with lower quality than image, get image
      Arguments.of(
        "application/pdf",
        listOf(MediaType("application", "pdf", 0.5), MediaType.IMAGE_JPEG),
        true,
        "image/jpeg",
      ),
      // PDF book: request PDF with higher quality than image, get pdf
      Arguments.of(
        "application/pdf",
        listOf(MediaType("image", "jpeg", 0.5), MediaType("application", "pdf", 0.8)),
        true,
        "application/pdf",
      ),
    )
}
