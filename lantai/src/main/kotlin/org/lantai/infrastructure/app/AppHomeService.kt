package org.lantai.infrastructure.app

import org.lantai.domain.model.BookSearch
import org.lantai.domain.model.LantaiUser
import org.lantai.domain.model.Media
import org.lantai.domain.model.ReadStatus
import org.lantai.domain.model.SearchCondition
import org.lantai.domain.model.SearchContext
import org.lantai.domain.model.SearchOperator
import org.lantai.domain.model.SeriesSearch
import org.lantai.domain.persistence.LibraryRepository
import org.lantai.interfaces.api.persistence.BookDtoRepository
import org.lantai.interfaces.api.persistence.SeriesDtoRepository
import org.lantai.interfaces.api.rest.dto.BookDto
import org.lantai.interfaces.api.rest.dto.SeriesDto
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.util.DigestUtils
import java.time.LocalDateTime

@Service
class AppHomeService(
  private val bookDtoRepository: BookDtoRepository,
  private val seriesDtoRepository: SeriesDtoRepository,
  private val libraryRepository: LibraryRepository,
  private val appProperties: AppProperties,
) {
  fun limits() =
    AppPaginationLimits(
      defaultLimit = appProperties.homeLimit.coerceIn(1, appProperties.maxHomeLimit),
      maxLimit = appProperties.maxHomeLimit,
    )

  fun home(
    user: LantaiUser,
    limit: Int,
    contextPath: String,
  ): AppHomeResult {
    val size = limit.coerceIn(1, appProperties.maxHomeLimit)
    val context = SearchContext(user)

    val readerStatus =
      SearchCondition.AnyOfBook(
        SearchCondition.MediaStatus(SearchOperator.Is(Media.Status.READY)),
        SearchCondition.MediaStatus(SearchOperator.Is(Media.Status.UNSUPPORTED)),
      )
    val continueReading =
      bookDtoRepository.findAll(
        BookSearch(
          condition =
            SearchCondition.AllOfBook(
              SearchCondition.ReadStatus(SearchOperator.Is(ReadStatus.IN_PROGRESS)),
              readerStatus,
            ),
        ),
        context,
        newest(size, "readProgress.readDate"),
      )
    val added =
      bookDtoRepository.findAll(
        BookSearch(condition = readerStatus),
        context,
        newest(size, "created"),
      )
    val published =
      bookDtoRepository.findAll(
        BookSearch(
          condition =
            SearchCondition.AllOfBook(
              SearchCondition.ReleaseDate(SearchOperator.IsNotNull),
              readerStatus,
            ),
        ),
        context,
        newest(size, "metadata.releaseDate"),
      )
    val recentRead =
      bookDtoRepository.findAll(
        BookSearch(
          condition =
            SearchCondition.AllOfBook(
              SearchCondition.AnyOfBook(
                SearchCondition.ReadStatus(SearchOperator.Is(ReadStatus.IN_PROGRESS)),
                SearchCondition.ReadStatus(SearchOperator.Is(ReadStatus.READ)),
              ),
              readerStatus,
            ),
        ),
        context,
        newest(size, "readProgress.readDate"),
      )
    val newSeries = seriesDtoRepository.findAll(SeriesSearch(), context, newest(size, "created"))
    val updatedSeries = seriesDtoRepository.findAllRecentlyUpdated(SeriesSearch(), context, newest(size, "lastModified"))

    val libraries =
      libraryRepository
        .findAll()
        .filter { user.canAccessLibrary(it.id) }
        .sortedBy { it.name.lowercase() }
        .map { AppLibraryCard(it.id, it.name) }

    val body =
      AppHomeDto(
        coverPath = "$contextPath/api/lantai/v1/app/cover",
        generatedAt = LocalDateTime.now(),
        limit = size,
        counts =
          AppHomeCounts(
            continueReading = continueReading.totalElements,
            added = added.totalElements,
            published = published.totalElements,
            recentRead = recentRead.totalElements,
            newSeries = newSeries.totalElements,
            updatedSeries = updatedSeries.totalElements,
          ),
        libraries = libraries,
        continueReading = continueReading.content.map { it.toCard(it.readProgress?.readDate) },
        added = added.content.map { it.toCard(it.created) },
        published = published.content.map { it.toCard(it.metadata.releaseDate?.atStartOfDay()) },
        recentRead = recentRead.content.map { it.toCard(it.readProgress?.readDate) },
        newSeries = newSeries.content.map { it.toSeriesCard(it.created) },
        updatedSeries = updatedSeries.content.map { it.toSeriesCard(it.lastModified) },
      )

    return AppHomeResult(fingerprint(body), body)
  }

  private fun fingerprint(body: AppHomeDto): String {
    val payload = StringBuilder(4096)
    with(body.counts) {
      payload
        .append(continueReading).append('/')
        .append(added).append('/')
        .append(published).append('/')
        .append(recentRead).append('/')
        .append(newSeries).append('/')
        .append(updatedSeries)
    }
    payload.append('#').append(body.libraries.size)
    body.continueReading.forEach { payload.append(it.id).append(it.at) }
    body.added.forEach { payload.append(it.id).append(it.at) }
    body.published.forEach { payload.append(it.id).append(it.at) }
    body.recentRead.forEach { payload.append(it.id).append(it.at) }
    body.newSeries.forEach { payload.append(it.id).append(it.at) }
    body.updatedSeries.forEach { payload.append(it.id).append(it.at) }
    return DigestUtils.md5DigestAsHex(payload.toString().toByteArray(Charsets.UTF_8))
  }

  private fun newest(
    size: Int,
    field: String,
  ): Pageable = PageRequest.of(0, size, Sort.by(Sort.Order.desc(field), Sort.Order.desc("id")))

  private fun BookDto.toCard(at: LocalDateTime?) =
    AppBookCard(
      id = id,
      title = metadata.title.takeIf { it.isNotBlank() } ?: name,
      seriesId = seriesId,
      seriesTitle = seriesTitle,
      number = metadata.number.takeIf { it.isNotBlank() } ?: formatNumber(metadata.numberSort),
      pages = media.pagesCount,
      profile = media.mediaProfile,
      page = readProgress?.page ?: 0,
      completed = readProgress?.completed ?: false,
      at = at,
    )

  private fun SeriesDto.toSeriesCard(at: LocalDateTime?) =
    AppSeriesCard(
      id = id,
      title = metadata.title.takeIf { it.isNotBlank() } ?: name,
      books = booksCount,
      read = booksReadCount,
      at = at,
    )

  private fun formatNumber(numberSort: Float): String {
    if (numberSort % 1f == 0f) return numberSort.toInt().toString()
    return String.format("%.1f", numberSort)
  }
}
