package org.lantai.infrastructure.security.apitoken

import org.lantai.domain.model.ApiToken
import org.springframework.stereotype.Component
import org.springframework.util.AntPathMatcher

@Component
class ApiTokenScopePolicy {
  private val matcher = AntPathMatcher()

  fun allows(
    scopes: Set<ApiToken.Scope>,
    method: String,
    path: String,
  ): Boolean {
    if (ApiToken.Scope.ADMIN in scopes) return true
    val rule =
      RULES.firstOrNull {
        (it.method == null || it.method.equals(method, ignoreCase = true)) && matcher.match(it.pattern, path)
      } ?: return false
    return rule.scope in scopes
  }

  private data class Rule(
    val method: String?,
    val pattern: String,
    val scope: ApiToken.Scope,
  )

  companion object {
    private val RULES =
      listOf(
        Rule("GET", "/api/lantai/v1/scrape/agent/queue", ApiToken.Scope.SCRAPE_READ),
        Rule("GET", "/api/lantai/v1/scrape/agent/queue/*", ApiToken.Scope.SCRAPE_READ),
        Rule("GET", "/api/lantai/v1/scrape/agent/stats", ApiToken.Scope.SCRAPE_READ),
        Rule("POST", "/api/lantai/v1/scrape/agent/queue/*/result", ApiToken.Scope.SCRAPE_SUBMIT),
        Rule("GET", "/api/v1/books/*/file", ApiToken.Scope.BOOKS_DOWNLOAD),
        Rule("GET", "/api/v1/books/*/file/**", ApiToken.Scope.BOOKS_DOWNLOAD),
        Rule("GET", "/api/v1/books/*/pages/**", ApiToken.Scope.PAGES_STREAM),
        Rule("GET", "/api/v1/books/*/thumbnail", ApiToken.Scope.PAGES_STREAM),
        Rule("GET", "/api/v1/books/*/resource/**", ApiToken.Scope.PAGES_STREAM),
        Rule("GET", "/api/v1/fonts/**", ApiToken.Scope.PAGES_STREAM),
        Rule("PATCH", "/api/v1/books/*/read-progress", ApiToken.Scope.PROGRESS_WRITE),
        Rule("PATCH", "/api/v1/books/*/readlist", ApiToken.Scope.PROGRESS_WRITE),
        Rule("POST", "/api/v1/books/*/metadata/refresh", ApiToken.Scope.METADATA_WRITE),
        Rule("PATCH", "/api/v1/books/metadata", ApiToken.Scope.METADATA_WRITE),
        Rule("PATCH", "/api/v1/books/*/metadata", ApiToken.Scope.METADATA_WRITE),
        Rule("POST", "/api/lantai/v1/bulk/metadata/submit", ApiToken.Scope.METADATA_WRITE),
        Rule("GET", "/api/lantai/v1/bulk/metadata/**", ApiToken.Scope.METADATA_READ),
        Rule("POST", "/api/lantai/v1/series/metadata/export", ApiToken.Scope.METADATA_READ),
        Rule("GET", "/api/lantai/v1/series/metadata/package/template", ApiToken.Scope.METADATA_READ),
        Rule("POST", "/api/lantai/v1/series/metadata/bulk", ApiToken.Scope.METADATA_WRITE),
        Rule("POST", "/api/lantai/v1/series/metadata/package", ApiToken.Scope.METADATA_WRITE),
        Rule(null, "/opds/**", ApiToken.Scope.OPDS_READ),
        Rule(null, "/kobo/**", ApiToken.Scope.KOBO_SYNC),
        Rule(null, "/koreader/**", ApiToken.Scope.KOREADER_SYNC),
        Rule("GET", "/api/lantai/v1/app/**", ApiToken.Scope.LIBRARY_READ),
        Rule("GET", "/api/lantai/v1/books/**", ApiToken.Scope.LIBRARY_READ),
        Rule("GET", "/api/lantai/v1/series/**", ApiToken.Scope.LIBRARY_READ),
        Rule("GET", "/api/lantai/v1/collections/**", ApiToken.Scope.LIBRARY_READ),
        Rule("GET", "/api/lantai/v1/readlists/**", ApiToken.Scope.LIBRARY_READ),
        Rule("GET", "/api/lantai/v1/annotations/**", ApiToken.Scope.LIBRARY_READ),
        Rule("GET", "/api/lantai/v1/reading/**", ApiToken.Scope.LIBRARY_READ),
        Rule("GET", "/api/lantai/v1/online/**", ApiToken.Scope.LIBRARY_READ),
        Rule("POST", "/api/lantai/v1/online/browse", ApiToken.Scope.LIBRARY_READ),
        Rule("GET", "/api/v1/libraries/**", ApiToken.Scope.LIBRARY_READ),
        Rule("GET", "/api/v2/libraries/**", ApiToken.Scope.LIBRARY_READ),
        Rule("GET", "/api/v1/series/**", ApiToken.Scope.LIBRARY_READ),
        Rule("GET", "/api/v2/series/**", ApiToken.Scope.LIBRARY_READ),
        Rule("GET", "/api/v1/books/**", ApiToken.Scope.LIBRARY_READ),
        Rule("GET", "/api/v2/books/**", ApiToken.Scope.LIBRARY_READ),
        Rule("GET", "/api/v1/referentials/**", ApiToken.Scope.LIBRARY_READ),
        Rule("GET", "/api/v2/referentials/**", ApiToken.Scope.LIBRARY_READ),
        Rule("GET", "/api/v1/search/**", ApiToken.Scope.LIBRARY_READ),
        Rule("GET", "/api/v2/search/**", ApiToken.Scope.LIBRARY_READ),
        Rule("GET", "/api/v2/collections/**", ApiToken.Scope.LIBRARY_READ),
        Rule("GET", "/api/v2/readlists/**", ApiToken.Scope.LIBRARY_READ),
      )
  }
}
