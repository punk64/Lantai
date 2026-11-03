package org.lantai.infrastructure.jooq.main

import org.assertj.core.api.Assertions.assertThat
import org.lantai.domain.model.BookPage
import org.lantai.domain.model.Media
import org.lantai.domain.model.PageHashKnown
import org.lantai.domain.model.makeBook
import org.lantai.domain.model.makeLibrary
import org.lantai.domain.model.makeSeries
import org.lantai.domain.persistence.BookRepository
import org.lantai.domain.persistence.LibraryRepository
import org.lantai.domain.persistence.SeriesRepository
import org.lantai.infrastructure.jooq.offset
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.Pageable
import java.time.LocalDateTime

@SpringBootTest
class PageHashDaoTest(
  @Autowired private val pageHashDao: PageHashDao,
  @Autowired private val mediaDao: MediaDao,
  @Autowired private val bookRepository: BookRepository,
  @Autowired private val seriesRepository: SeriesRepository,
  @Autowired private val libraryRepository: LibraryRepository,
) {
  private val library = makeLibrary()
  private val series = makeSeries("Series", libraryId = library.id)
  private val books =
    listOf(
      makeBook("Book", libraryId = library.id, seriesId = series.id),
      makeBook("Book2", libraryId = library.id, seriesId = series.id),
    )

  @BeforeAll
  fun setup() {
    libraryRepository.insert(library)
    seriesRepository.insert(series)
    bookRepository.insert(books)
  }

  @AfterEach
  fun deleteMedia() {
    bookRepository.findAll().forEach {
      mediaDao.delete(it.id)
    }
  }

  @AfterAll
  fun tearDown() {
    bookRepository.deleteAll()
    seriesRepository.deleteAll()
    libraryRepository.deleteAll()
  }

  @Nested
  inner class Known {
    @Test
    fun `given a known page hash when inserting then it is persisted`() {
      val now = LocalDateTime.now()
      val pageHash =
        PageHashKnown(
          hash = "hashed",
          size = 10,
          action = PageHashKnown.Action.IGNORE,
        )

      pageHashDao.insert(pageHash, null)
      val known = pageHashDao.findKnown(pageHash.hash)!!

      assertThat(known.hash).isEqualTo(pageHash.hash)
      assertThat(known.size).isEqualTo(pageHash.size)
      assertThat(known.action).isEqualTo(pageHash.action)
      assertThat(known.createdDate).isCloseTo(now, offset)
      assertThat(known.lastModifiedDate).isCloseTo(now, offset)
    }
  }

  @Nested
  inner class Unknown {
    @Test
    fun `given known hashes when finding unknown then known are not included`() {
      // given
      pageHashDao.insert(
        PageHashKnown(
          hash = "hash-1",
          size = 1,
          action = PageHashKnown.Action.IGNORE,
        ),
        null,
      )

      pageHashDao.insert(
        PageHashKnown(
          hash = "hash-2",
          size = null,
          action = PageHashKnown.Action.IGNORE,
        ),
        null,
      )

      val media =
        Media(
          status = Media.Status.READY,
          mediaType = "application/zip",
          pages =
            (1..10).map {
              BookPage(
                fileName = "$it.jpg",
                mediaType = "image/jpeg",
                fileHash = "hash-$it",
                fileSize = it.toLong(),
              )
            },
          comment = "comment",
          bookId = books.first().id,
        )
      mediaDao.insert(media)
      mediaDao.insert(media.copy(bookId = books.last().id))

      // when
      val unknown = pageHashDao.findAllUnknown(Pageable.unpaged())

      // then
      assertThat(unknown).hasSize(8)
      assertThat(unknown.map { it.hash })
        .doesNotContain("hash-1")
        .doesNotContain("hash-2")
        .containsExactlyInAnyOrderElementsOf((3..10).map { "hash-$it" })
    }
  }
}
