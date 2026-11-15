package org.lantai.interfaces.api.rest

import com.github.benmanes.caffeine.cache.Caffeine
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.lantai.domain.persistence.LantaiUserRepository
import org.lantai.infrastructure.openapi.OpenApiConfiguration
import org.lantai.infrastructure.security.LantaiPrincipal
import org.lantai.interfaces.api.rest.dto.JsonFeedDto
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.function.client.WebClient
import java.util.concurrent.TimeUnit

@RestController
@RequestMapping("api/v1/announcements", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = OpenApiConfiguration.TagNames.ANNOUNCEMENTS)
class AnnouncementController(
  private val userRepository: LantaiUserRepository,
  private val webClientBuilder: WebClient.Builder,
  @param:Value("\${lantai.announcements.feed-url:}") private val feedUrl: String,
) {
  private val webClient by lazy { webClientBuilder.baseUrl(feedUrl).build() }

  private val cache =
    Caffeine
      .newBuilder()
      .expireAfterAccess(1, TimeUnit.HOURS)
      .build<String, JsonFeedDto?>()

  @GetMapping
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Retrieve announcements")
  fun getAnnouncements(
    @AuthenticationPrincipal principal: LantaiPrincipal,
  ): JsonFeedDto {
    val feed = cache.get("announcements") { fetchWebsiteAnnouncements() } ?: return emptyFeed()
    val read = userRepository.findAnnouncementIdsReadByUserId(principal.user.id)
    return feed.copy(items = feed.items.map { item -> item.copy(lantaiExtension = JsonFeedDto.LantaiExtensionDto(read.contains(item.id))) })
  }

  private fun emptyFeed() = JsonFeedDto(version = "https://jsonfeed.org/version/1", title = "Lantai", homePageUrl = null, description = null)

  @PreAuthorize("hasRole('ADMIN')")
  @PutMapping
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Mark announcements as read")
  fun markAnnouncementsRead(
    @AuthenticationPrincipal principal: LantaiPrincipal,
    @RequestBody announcementIds: Set<String>,
  ) {
    userRepository.saveAnnouncementIdsRead(principal.user, announcementIds)
  }

  fun fetchWebsiteAnnouncements(): JsonFeedDto? {
    if (feedUrl.isBlank()) return null
    val response =
      webClient
        .get()
        .retrieve()
        .toEntity(JsonFeedDto::class.java)
        .block()
    return response?.body
  }
}
