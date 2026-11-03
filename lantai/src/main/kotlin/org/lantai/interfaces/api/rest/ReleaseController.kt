package org.lantai.interfaces.api.rest

import com.github.benmanes.caffeine.cache.Caffeine
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.lantai.infrastructure.configuration.LantaiProperties
import org.lantai.infrastructure.openapi.OpenApiConfiguration
import org.lantai.interfaces.api.rest.dto.GithubReleaseDto
import org.lantai.interfaces.api.rest.dto.ReleaseDto
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.function.client.WebClient
import java.util.concurrent.TimeUnit

private const val GITHUB_RELEASES = "https://api.github.com/repos/%s/releases"

@RestController
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("api/v1/releases", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = OpenApiConfiguration.TagNames.RELEASES)
class ReleaseController(
  webClientBuilder: WebClient.Builder,
  properties: LantaiProperties,
) {
  private val webClient: WebClient? =
    properties.updateCheck.repo.trim().takeIf { it.isNotEmpty() }?.let { repo ->
      webClientBuilder.baseUrl(GITHUB_RELEASES.format(repo)).build()
    }

  private val cache =
    Caffeine
      .newBuilder()
      .expireAfterAccess(1, TimeUnit.HOURS)
      .build<String, List<GithubReleaseDto>>()

  @GetMapping
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "List releases")
  fun getReleases(): List<ReleaseDto> {
    val client = webClient ?: return emptyList()
    return cache
      .get("releases") { fetchGitHubReleases(client) }
      .let { releases ->
        releases.mapIndexed { index, ghRel ->
          ReleaseDto(
            ghRel.tagName,
            ghRel.publishedAt,
            ghRel.htmlUrl,
            index == 0,
            ghRel.prerelease,
            ghRel.body,
          )
        }
      }
  }

  private fun fetchGitHubReleases(client: WebClient): List<GithubReleaseDto> {
    val response =
      client
        .get()
        .uri {
          it.queryParam("per_page", 20).build()
        }.retrieve()
        .toEntity(object : ParameterizedTypeReference<List<GithubReleaseDto>>() {})
        .block()
    return response?.body ?: emptyList()
  }
}
