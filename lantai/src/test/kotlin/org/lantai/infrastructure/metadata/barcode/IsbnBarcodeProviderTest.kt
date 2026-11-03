package org.lantai.infrastructure.metadata.barcode

import io.mockk.every
import io.mockk.mockk
import org.apache.commons.validator.routines.ISBNValidator
import org.assertj.core.api.Assertions.assertThat
import org.lantai.domain.model.BookPage
import org.lantai.domain.model.BookWithMedia
import org.lantai.domain.model.Media
import org.lantai.domain.model.makeBook
import org.lantai.domain.service.BookAnalyzer
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource

class IsbnBarcodeProviderTest {
  private val mockAnalyzer = mockk<BookAnalyzer>()
  private val isbnBarcodeProvider = IsbnBarcodeProvider(mockAnalyzer, ISBNValidator(true))

  @Test
  fun `given book page with barcode when getting book metadata then ISBN is returned`() {
    // given
    val file = ClassPathResource("barcode/page_384.jpg").file
    every { mockAnalyzer.getPageContent(any(), any()) } returns file.readBytes()

    val book = makeBook("Book1")
    val media = Media(pages = listOf(BookPage("page", "image/jpeg")))

    // when
    val patch = isbnBarcodeProvider.getBookMetadataFromBook(BookWithMedia(book, media))

    // then
    assertThat(patch?.isbn).isEqualTo("9782811632397")
  }

  @Test
  fun `given invalid image page when getting book metadata then patch is null`() {
    // given
    every { mockAnalyzer.getPageContent(any(), any()) } returns ByteArray(0)

    val book = makeBook("Book1")
    val media = Media(pages = listOf(BookPage("page", "image/jpeg")))

    // when
    val patch = isbnBarcodeProvider.getBookMetadataFromBook(BookWithMedia(book, media))

    // then
    assertThat(patch).isNull()
  }

  @Test
  fun `given page without barcode when getting book metadata then patch is null`() {
    // given
    val file = ClassPathResource("barcode/lantai.png").file
    every { mockAnalyzer.getPageContent(any(), any()) } returns file.readBytes()

    val book = makeBook("Book1")
    val media = Media(pages = listOf(BookPage("page", "image/jpeg")))

    // when
    val patch = isbnBarcodeProvider.getBookMetadataFromBook(BookWithMedia(book, media))

    // then
    assertThat(patch).isNull()
  }
}
