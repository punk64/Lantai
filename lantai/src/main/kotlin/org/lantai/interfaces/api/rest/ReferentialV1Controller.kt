package org.lantai.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.lantai.domain.persistence.ReferentialRepository
import org.lantai.infrastructure.openapi.OpenApiConfiguration
import org.lantai.infrastructure.security.LantaiPrincipal
import org.lantai.interfaces.api.rest.dto.AuthorDto
import org.lantai.interfaces.api.rest.dto.toDto
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("api/v1", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = OpenApiConfiguration.TagNames.REFERENTIAL)
class ReferentialV1Controller(
  private val referentialRepository: ReferentialRepository,
) {
  @GetMapping("authors")
  @Operation(summary = "List authors", description = "Komga v1 compatible endpoint. Use GET /api/v2/authors for new integrations.", tags = [OpenApiConfiguration.TagNames.REFERENTIAL])
  fun getAuthorsDeprecated(
    @AuthenticationPrincipal principal: LantaiPrincipal,
    @RequestParam(name = "search", defaultValue = "") search: String,
    @RequestParam(name = "library_id", required = false) libraryId: String?,
    @RequestParam(name = "collection_id", required = false) collectionId: String?,
    @RequestParam(name = "series_id", required = false) seriesId: String?,
  ): List<AuthorDto> =

    when {
      libraryId != null -> referentialRepository.findAllAuthorsByNameAndLibrary(search, libraryId, principal.user.getAuthorizedLibraryIds(null))
      collectionId != null -> referentialRepository.findAllAuthorsByNameAndCollection(search, collectionId, principal.user.getAuthorizedLibraryIds(null))
      seriesId != null -> referentialRepository.findAllAuthorsByNameAndSeries(search, seriesId, principal.user.getAuthorizedLibraryIds(null))
      else -> referentialRepository.findAllAuthorsByName(search, principal.user.getAuthorizedLibraryIds(null))
    }.map { it.toDto() }

  @GetMapping("authors/names")
  @Operation(summary = "List authors' names", description = "Komga v1 compatible endpoint. Use GET /v2/authors/names for new integrations.", tags = [OpenApiConfiguration.TagNames.REFERENTIAL])
  fun getAuthorsNames(
    @AuthenticationPrincipal principal: LantaiPrincipal,
    @RequestParam(name = "search", defaultValue = "") search: String,
  ): List<String> = referentialRepository.findAllAuthorsNamesByName(search, principal.user.getAuthorizedLibraryIds(null))

  @GetMapping("authors/roles")
  @Operation(summary = "List authors' roles", description = "Komga v1 compatible endpoint. Use GET /v2/authors/roles for new integrations.", tags = [OpenApiConfiguration.TagNames.REFERENTIAL])
  fun getAuthorsRoles(
    @AuthenticationPrincipal principal: LantaiPrincipal,
  ): List<String> = referentialRepository.findAllAuthorsRoles(principal.user.getAuthorizedLibraryIds(null))

  @GetMapping("genres")
  @Operation(summary = "List genres", description = "Komga v1 compatible endpoint. Use GET /v2/genres for new integrations.", tags = [OpenApiConfiguration.TagNames.REFERENTIAL])
  fun getGenres(
    @AuthenticationPrincipal principal: LantaiPrincipal,
    @RequestParam(name = "library_id", required = false) libraryIds: Set<String> = emptySet(),
    @RequestParam(name = "collection_id", required = false) collectionId: String?,
  ): Set<String> =
    when {
      libraryIds.isNotEmpty() -> referentialRepository.findAllGenresByLibraries(libraryIds, principal.user.getAuthorizedLibraryIds(null))
      collectionId != null -> referentialRepository.findAllGenresByCollection(collectionId, principal.user.getAuthorizedLibraryIds(null))
      else -> referentialRepository.findAllGenres(principal.user.getAuthorizedLibraryIds(null))
    }

  @GetMapping("sharing-labels")
  @Operation(summary = "List sharing labels", description = "Komga v1 compatible endpoint. Use GET /v2/sharing-labels for new integrations.", tags = [OpenApiConfiguration.TagNames.REFERENTIAL])
  fun getSharingLabels(
    @AuthenticationPrincipal principal: LantaiPrincipal,
    @RequestParam(name = "library_id", required = false) libraryIds: Set<String> = emptySet(),
    @RequestParam(name = "collection_id", required = false) collectionId: String?,
  ): Set<String> =
    when {
      libraryIds.isNotEmpty() -> referentialRepository.findAllSharingLabelsByLibraries(libraryIds, principal.user.getAuthorizedLibraryIds(null))
      collectionId != null -> referentialRepository.findAllSharingLabelsByCollection(collectionId, principal.user.getAuthorizedLibraryIds(null))
      else -> referentialRepository.findAllSharingLabels(principal.user.getAuthorizedLibraryIds(null))
    }

  @GetMapping("tags")
  @Operation(summary = "List tags", description = "Komga v1 compatible endpoint. Use GET /v2/tags for new integrations.", tags = [OpenApiConfiguration.TagNames.REFERENTIAL])
  fun getTags(
    @AuthenticationPrincipal principal: LantaiPrincipal,
    @RequestParam(name = "library_id", required = false) libraryIds: Set<String> = emptySet(),
    @RequestParam(name = "collection_id", required = false) collectionId: String?,
  ): Set<String> =
    when {
      libraryIds.isNotEmpty() -> referentialRepository.findAllSeriesAndBookTagsByLibraries(libraryIds, principal.user.getAuthorizedLibraryIds(null))
      collectionId != null -> referentialRepository.findAllSeriesAndBookTagsByCollection(collectionId, principal.user.getAuthorizedLibraryIds(null))
      else -> referentialRepository.findAllSeriesAndBookTags(principal.user.getAuthorizedLibraryIds(null))
    }

  @GetMapping("tags/book")
  @Operation(summary = "List book tags", description = "Komga v1 compatible endpoint. Use GET /v2/tags for new integrations.", tags = [OpenApiConfiguration.TagNames.REFERENTIAL])
  fun getBookTags(
    @AuthenticationPrincipal principal: LantaiPrincipal,
    @RequestParam(name = "series_id", required = false) seriesId: String?,
    @RequestParam(name = "readlist_id", required = false) readListId: String?,
    @RequestParam(name = "library_id", required = false) libraryIds: Set<String> = emptySet(),
  ): Set<String> =
    when {
      seriesId != null -> referentialRepository.findAllBookTagsBySeries(seriesId, principal.user.getAuthorizedLibraryIds(null))
      readListId != null -> referentialRepository.findAllBookTagsByReadList(readListId, principal.user.getAuthorizedLibraryIds(null))
      libraryIds.isNotEmpty() -> referentialRepository.findAllBookTags(principal.user.getAuthorizedLibraryIds(libraryIds))
      else -> referentialRepository.findAllBookTags(principal.user.getAuthorizedLibraryIds(null))
    }

  @GetMapping("tags/series")
  @Operation(summary = "List series tags", description = "Komga v1 compatible endpoint. Use GET /v2/tags for new integrations.", tags = [OpenApiConfiguration.TagNames.REFERENTIAL])
  fun getSeriesTags(
    @AuthenticationPrincipal principal: LantaiPrincipal,
    @RequestParam(name = "library_id", required = false) libraryId: String?,
    @RequestParam(name = "collection_id", required = false) collectionId: String?,
  ): Set<String> =
    when {
      libraryId != null -> referentialRepository.findAllSeriesTagsByLibrary(libraryId, principal.user.getAuthorizedLibraryIds(null))
      collectionId != null -> referentialRepository.findAllSeriesTagsByCollection(collectionId, principal.user.getAuthorizedLibraryIds(null))
      else -> referentialRepository.findAllSeriesTags(principal.user.getAuthorizedLibraryIds(null))
    }

  @GetMapping("languages")
  @Operation(summary = "List languages", description = "Komga v1 compatible endpoint. Use GET /v2/languages for new integrations.", tags = [OpenApiConfiguration.TagNames.REFERENTIAL])
  fun getLanguages(
    @AuthenticationPrincipal principal: LantaiPrincipal,
    @RequestParam(name = "library_id", required = false) libraryIds: Set<String> = emptySet(),
    @RequestParam(name = "collection_id", required = false) collectionId: String?,
  ): Set<String> =
    when {
      libraryIds.isNotEmpty() -> referentialRepository.findAllLanguagesByLibraries(libraryIds, principal.user.getAuthorizedLibraryIds(null))
      collectionId != null -> referentialRepository.findAllLanguagesByCollection(collectionId, principal.user.getAuthorizedLibraryIds(null))
      else -> referentialRepository.findAllLanguages(principal.user.getAuthorizedLibraryIds(null))
    }

  @GetMapping("publishers")
  @Operation(summary = "List publishers", description = "Komga v1 compatible endpoint. Use GET /v2/publishers for new integrations.", tags = [OpenApiConfiguration.TagNames.REFERENTIAL])
  fun getPublishers(
    @AuthenticationPrincipal principal: LantaiPrincipal,
    @RequestParam(name = "library_id", required = false) libraryIds: Set<String> = emptySet(),
    @RequestParam(name = "collection_id", required = false) collectionId: String?,
  ): Set<String> =
    when {
      libraryIds.isNotEmpty() -> referentialRepository.findAllPublishersByLibraries(libraryIds, principal.user.getAuthorizedLibraryIds(null))
      collectionId != null -> referentialRepository.findAllPublishersByCollection(collectionId, principal.user.getAuthorizedLibraryIds(null))
      else -> referentialRepository.findAllPublishers(principal.user.getAuthorizedLibraryIds(null))
    }

  @GetMapping("age-ratings")
  @Operation(summary = "List age ratings", description = "Komga v1 compatible endpoint. Use GET /v2/age-ratings for new integrations.", tags = [OpenApiConfiguration.TagNames.REFERENTIAL])
  fun getAgeRatings(
    @AuthenticationPrincipal principal: LantaiPrincipal,
    @RequestParam(name = "library_id", required = false) libraryIds: Set<String> = emptySet(),
    @RequestParam(name = "collection_id", required = false) collectionId: String?,
  ): Set<String> =
    when {
      libraryIds.isNotEmpty() -> referentialRepository.findAllAgeRatingsByLibraries(libraryIds, principal.user.getAuthorizedLibraryIds(null))
      collectionId != null -> referentialRepository.findAllAgeRatingsByCollection(collectionId, principal.user.getAuthorizedLibraryIds(null))
      else -> referentialRepository.findAllAgeRatings(principal.user.getAuthorizedLibraryIds(null))
    }.map { it?.toString() ?: "None" }.toSet()

  @GetMapping("series/release-dates")
  @Operation(summary = "List series release dates", description = "Komga v1 compatible endpoint. Use GET /v2/series/release-years for new integrations.", tags = [OpenApiConfiguration.TagNames.REFERENTIAL])
  fun getSeriesReleaseDates(
    @AuthenticationPrincipal principal: LantaiPrincipal,
    @RequestParam(name = "library_id", required = false) libraryIds: Set<String> = emptySet(),
    @RequestParam(name = "collection_id", required = false) collectionId: String?,
  ): Set<String> =
    when {
      libraryIds.isNotEmpty() -> referentialRepository.findAllSeriesReleaseDatesByLibraries(libraryIds, principal.user.getAuthorizedLibraryIds(null))
      collectionId != null -> referentialRepository.findAllSeriesReleaseDatesByCollection(collectionId, principal.user.getAuthorizedLibraryIds(null))
      else -> referentialRepository.findAllSeriesReleaseDates(principal.user.getAuthorizedLibraryIds(null))
    }.map { it.year.toString() }.toSet()
}
