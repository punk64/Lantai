package org.lantai.infrastructure.plugin

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.lantai.domain.model.ApiToken
import org.lantai.domain.model.PluginCapabilities
import org.lantai.infrastructure.security.apitoken.ApiTokenScopePolicy

class PluginP6VerificationTest {
  private val policy = ApiTokenScopePolicy()

  @Test
  fun `library write covers library mutation`() {
    val scopes = setOf(ApiToken.Scope.LIBRARY_WRITE)
    assertTrue(policy.allows(scopes, "POST", "/api/v1/libraries"))
    assertTrue(policy.allows(scopes, "PATCH", "/api/v1/libraries/abc"))
    assertTrue(policy.allows(scopes, "POST", "/api/v1/libraries/abc/scan"))
    assertTrue(policy.allows(scopes, "POST", "/api/v1/libraries/abc/empty-trash"))
  }

  @Test
  fun `library write covers trash chain`() {
    val scopes = setOf(ApiToken.Scope.LIBRARY_WRITE)
    assertTrue(policy.allows(scopes, "POST", "/api/lantai/v1/trash/abc/restore"))
    assertTrue(policy.allows(scopes, "DELETE", "/api/lantai/v1/trash/abc"))
    assertTrue(policy.allows(scopes, "DELETE", "/api/lantai/v1/trash"))
    assertTrue(policy.allows(scopes, "DELETE", "/api/lantai/v1/trash/cache/abc"))
    assertTrue(policy.allows(scopes, "DELETE", "/api/lantai/v1/trash/excluded/abc"))
    assertTrue(policy.allows(scopes, "DELETE", "/api/lantai/v1/trash/excluded/books/abc"))
  }

  @Test
  fun `library write covers file deletion and thumbnails`() {
    val scopes = setOf(ApiToken.Scope.LIBRARY_WRITE)
    assertTrue(policy.allows(scopes, "DELETE", "/api/v1/books/abc/file"))
    assertTrue(policy.allows(scopes, "DELETE", "/api/v1/series/abc/file"))
    assertTrue(policy.allows(scopes, "PUT", "/api/v1/books/abc/thumbnails/t1/selected"))
    assertTrue(policy.allows(scopes, "DELETE", "/api/v1/books/abc/thumbnails/t1"))
    assertTrue(policy.allows(scopes, "PUT", "/api/v1/series/abc/thumbnails/t1/selected"))
  }

  @Test
  fun `library write does not grant read or import`() {
    val scopes = setOf(ApiToken.Scope.LIBRARY_WRITE)
    assertFalse(policy.allows(scopes, "GET", "/api/v1/books/abc/file"))
    assertFalse(policy.allows(scopes, "POST", "/api/v1/books/import"))
    assertFalse(policy.allows(scopes, "GET", "/api/v1/libraries"))
  }

  @Test
  fun `library import covers book import only`() {
    val scopes = setOf(ApiToken.Scope.LIBRARY_IMPORT)
    assertTrue(policy.allows(scopes, "POST", "/api/v1/books/import"))
    assertFalse(policy.allows(scopes, "POST", "/api/v1/libraries"))
    assertFalse(policy.allows(scopes, "DELETE", "/api/v1/books/abc/file"))
  }

  @Test
  fun `library read may browse trash but not delete`() {
    val scopes = setOf(ApiToken.Scope.LIBRARY_READ)
    assertTrue(policy.allows(scopes, "GET", "/api/lantai/v1/trash"))
    assertTrue(policy.allows(scopes, "GET", "/api/lantai/v1/trash/settings"))
    assertFalse(policy.allows(scopes, "DELETE", "/api/lantai/v1/trash/abc"))
    assertFalse(policy.allows(scopes, "POST", "/api/lantai/v1/trash/abc/restore"))
  }

  @Test
  fun `admin scope allows everything`() {
    val scopes = setOf(ApiToken.Scope.ADMIN)
    assertTrue(policy.allows(scopes, "DELETE", "/api/lantai/v1/trash/abc"))
    assertTrue(policy.allows(scopes, "POST", "/api/v1/books/import"))
    assertTrue(policy.allows(scopes, "GET", "/api/v1/books"))
  }

  @Test
  fun `unknown path stays denied`() {
    assertFalse(policy.allows(emptySet(), "POST", "/api/v1/libraries"))
    assertFalse(policy.allows(setOf(ApiToken.Scope.LIBRARY_READ), "POST", "/api/v1/libraries"))
  }

  @Test
  fun `provider capabilities are known`() {
    listOf(
      "metadata.provide",
      "font.provide",
      "page.transform",
      "text.transform",
      "search.extend",
      "convert.provide",
    ).forEach { assertTrue(PluginCapabilities.isKnown(it), it) }
  }

  @Test
  fun `duplicate capability names are rejected`() {
    listOf("notify.channel", "reading.read", "shelf.manage", "settings.own").forEach {
      assertFalse(PluginCapabilities.isKnown(it), it)
    }
  }
}
