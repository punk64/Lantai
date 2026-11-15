package org.lantai.infrastructure.plugin

import org.lantai.domain.persistence.PluginStore
import org.springframework.stereotype.Component

@Component
class PluginContentTransform(
  private val store: PluginStore,
  private val instances: PluginInstanceService,
  private val httpClient: PluginHttpClient,
) {
  fun transformPage(
    bookId: String,
    pageNumber: Int,
    bytes: ByteArray,
    contentType: String,
  ): ByteArray {
    if (bytes.isEmpty() || !SAFE_ID.matches(bookId)) return bytes
    val path = "/v1/capabilities/$PAGE_CAPABILITY?bookId=$bookId&page=$pageNumber"
    return callPlugin(PAGE_CAPABILITY, path, bytes, contentType, PAGE_TIMEOUT, PAGE_MAX_BYTES) ?: bytes
  }

  fun transformText(
    bookId: String,
    chapter: Int,
    text: String,
  ): String? {
    if (text.isEmpty() || !SAFE_ID.matches(bookId)) return null
    val path = "/v1/capabilities/$TEXT_CAPABILITY?bookId=$bookId&chapter=$chapter"
    val bytes = callPlugin(TEXT_CAPABILITY, path, text.toByteArray(Charsets.UTF_8), "text/html;charset=UTF-8", TEXT_TIMEOUT, TEXT_MAX_BYTES)
    return bytes?.toString(Charsets.UTF_8)
  }

  private fun callPlugin(
    capability: String,
    path: String,
    body: ByteArray,
    contentType: String,
    timeoutSeconds: Int,
    maxBytes: Int,
  ): ByteArray? {
    for (candidate in store.findProviders(capability)) {
      val ready = runCatching { instances.prepare(candidate.pluginId) }.getOrNull() ?: continue
      val endpoint = ready.endpoint?.takeIf { it.isNotBlank() } ?: continue
      val call =
        runCatching {
          httpClient.invokePostRaw(endpoint, path, ready.hostKey, body, contentType, timeoutSeconds, maxBytes)
        }.getOrNull()
      if (call?.ok == true) {
        val result = call.body ?: continue
        if (result.isNotEmpty()) return result
      }
    }
    return null
  }

  private companion object {
    private const val PAGE_CAPABILITY = "page.transform"
    private const val TEXT_CAPABILITY = "text.transform"
    private const val PAGE_TIMEOUT = 15
    private const val TEXT_TIMEOUT = 15
    private const val PAGE_MAX_BYTES = 16 * 1024 * 1024
    private const val TEXT_MAX_BYTES = 2 * 1024 * 1024
    private val SAFE_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
  }
}
