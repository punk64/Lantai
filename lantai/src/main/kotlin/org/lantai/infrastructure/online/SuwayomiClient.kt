package org.lantai.infrastructure.online

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.util.UriComponentsBuilder
import java.net.URI
import java.time.Duration
import java.util.Base64

private val logger = KotlinLogging.logger {}

private const val SETTINGS_QUERY =
  "{ settings { ip port authMode downloadAsCbz downloadsPath autoDownloadNewChapters excludeEntryWithUnreadChapters " +
    "autoDownloadNewChaptersLimit maxSourcesInParallel globalUpdateInterval updateMangas backupInterval backupPath } }"

private const val EXTENSIONS_QUERY =
  "{ extensions { totalCount nodes { pkgName name versionName lang isNsfw isInstalled hasUpdate isObsolete iconUrl storeIndexUrl apkUrl jarUrl } } }"

private const val STORES_QUERY =
  "{ extensionStores { totalCount nodes { name indexUrl extensionListUrl isLegacy } } }"

@Component
class SuwayomiClient(
  private val properties: OnlineProperties,
  private val configWriter: SuwayomiConfigWriter,
) {
  private val objectMapper = ObjectMapper()

  private val requestFactory =
    SimpleClientHttpRequestFactory().apply {
      setConnectTimeout(Duration.ofSeconds(5))
      setReadTimeout(Duration.ofSeconds(60))
    }

  private val client: RestClient by lazy {
    RestClient
      .builder()
      .requestFactory(requestFactory)
      .build()
  }

  private val authToken: String?
    get() {
      if (properties.suwayomiUsername.isBlank()) {
        configWriter.readCredentials()?.let { credentials ->
          if (credentials.username.isNotBlank()) {
            properties.suwayomiUsername = credentials.username
            properties.suwayomiPassword = credentials.password
          }
        }
      }
      return if (properties.suwayomiUsername.isBlank()) {
        null
      } else {
        "Basic " +
          Base64
            .getEncoder()
            .encodeToString("${properties.suwayomiUsername}:${properties.suwayomiPassword}".toByteArray(Charsets.UTF_8))
      }
    }

  private fun <T : RestClient.RequestHeadersSpec<T>> T.withAuth(accept: String = "application/json"): T =
    apply {
      header(HttpHeaders.ACCEPT, accept)
      authToken?.let { header(HttpHeaders.AUTHORIZATION, it) }
    }

  fun isEnabled(): Boolean = properties.enabled && properties.suwayomiEndpoint.isNotBlank()

  fun about(): SuwayomiAbout = get("/api/v1/settings/about")

  fun settings(): JsonNode = graphQLData(mapOf("query" to SETTINGS_QUERY)).path("settings")

  fun setSettings(values: Map<String, Any>): JsonNode =
    graphQLData(
      mapOf(
        "query" to
          "mutation(\$input: SetSettingsInput!) { setSettings(input: \$input) { settings { downloadAsCbz downloadsPath maxSourcesInParallel globalUpdateInterval } } }",
        "variables" to mapOf("input" to mapOf("settings" to values)),
      ),
    )

  fun listExtensions(): List<SuwayomiExtension> =
    graphQLData(mapOf("query" to EXTENSIONS_QUERY))
      .path("extensions")
      .path("nodes")
      .mapNotNull { node ->
        val pkgName = node.path("pkgName").asText(null) ?: return@mapNotNull null
        SuwayomiExtension(
          pkgName = pkgName,
          name = node.path("name").asText(null),
          versionName = node.path("versionName").asText(null),
          lang = node.path("lang").asText(null),
          isNsfw = node.path("isNsfw").asBoolean(false),
          isInstalled = node.path("isInstalled").asBoolean(false),
          hasUpdate = node.path("hasUpdate").asBoolean(false),
          isObsolete = node.path("isObsolete").asBoolean(false),
          iconUrl = node.path("iconUrl").asText(null),
          storeIndexUrl = node.path("storeIndexUrl").asText(null),
          apkUrl = node.path("apkUrl").asText(null),
          jarUrl = node.path("jarUrl").asText(null),
        )
      }

  fun listExtensionStores(): List<SuwayomiExtensionStore> =
    graphQLData(mapOf("query" to STORES_QUERY))
      .path("extensionStores")
      .path("nodes")
      .mapNotNull { node ->
        val name = node.path("name").asText(null) ?: return@mapNotNull null
        SuwayomiExtensionStore(
          name = name,
          indexUrl = node.path("indexUrl").asText(null),
          extensionListUrl = node.path("extensionListUrl").asText(null),
          isLegacy = node.path("isLegacy").asBoolean(false),
        )
      }

  fun addExtensionStore(indexUrl: String): JsonNode =
    graphQLData(
      mapOf(
        "query" to "mutation(\$input: AddExtensionStoreInput!) { addExtensionStore(input: \$input) { extensionStore { name indexUrl } } }",
        "variables" to mapOf("input" to mapOf("indexUrl" to indexUrl)),
      ),
    )

  fun removeExtensionStore(indexUrl: String): JsonNode =
    graphQLData(
      mapOf(
        "query" to
          "mutation(\$input: RemoveExtensionStoreInput!) { removeExtensionStore(input: \$input) { extensionStore { indexUrl } } }",
        "variables" to mapOf("input" to mapOf("indexUrl" to indexUrl)),
      ),
    )

  fun fetchExtensions(): JsonNode =
    graphQLData(mapOf("query" to "mutation { fetchExtensions(input: {}) { extensions { pkgName isInstalled } } }"))

  fun patchExtension(
    pkgName: String,
    install: Boolean? = null,
    update: Boolean? = null,
    uninstall: Boolean? = null,
  ): JsonNode {
    val patch = mutableMapOf<String, Any>()
    install?.let { patch["install"] = it }
    update?.let { patch["update"] = it }
    uninstall?.let { patch["uninstall"] = it }
    return graphQLData(
      mapOf(
        "query" to
          "mutation(\$input: UpdateExtensionInput!) { updateExtension(input: \$input) { extension { pkgName isInstalled versionName } } }",
        "variables" to mapOf("input" to mapOf("id" to pkgName, "patch" to patch)),
      ),
    )
  }

  fun installExternalExtension(
    bytes: ByteArray,
    fileName: String,
  ): JsonNode {
    val operations =
      objectMapper.writeValueAsString(
        mapOf(
          "query" to
            "mutation(\$file: Upload!) { installExternalExtension(input: { extensionFile: \$file }) { extension { pkgName isInstalled versionName } } }",
          "variables" to mapOf("file" to null),
        ),
      )
    val multipart = LinkedMultiValueMap<String, Any>()
    multipart.add("operations", operations)
    multipart.add("map", """{"0":["variables.file"]}""")
    multipart.add(
      "0",
      object : ByteArrayResource(bytes) {
        override fun getFilename(): String = fileName
      },
    )
    val response =
      try {
        client
          .post()
          .uri(uri("/api/graphql"))
          .withAuth()
          .contentType(MediaType.MULTIPART_FORM_DATA)
          .body(multipart)
          .retrieve()
          .body(JsonNode::class.java)
          ?: throw SuwayomiException("Empty GraphQL response for extension install")
      } catch (e: RestClientResponseException) {
        throw SuwayomiException("Suwayomi extension install responded with ${e.statusCode}", e)
      } catch (e: SuwayomiException) {
        throw e
      } catch (e: Exception) {
        throw SuwayomiException("Suwayomi extension install failed: ${e.message}", e)
      }
    return checkErrors(response).path("data")
  }

  fun sourceList(): List<SuwayomiSource> = getArray("/api/v1/source/list", SuwayomiSource::class.java)

  fun source(sourceId: String): SuwayomiSource? = sourceList().firstOrNull { it.id == sourceId }

  fun search(
    sourceId: String,
    query: String,
    page: Int = 1,
  ): SuwayomiSearchResult =
    get(
      "/api/v1/source/$sourceId/search",
      "searchTerm" to query,
      "pageNum" to page.toString(),
    )

  fun fetchSourceManga(
    sourceId: String,
    type: String,
    query: String?,
    page: Int,
    filters: List<SuwayomiFilterState> = emptyList(),
  ): SuwayomiBrowseResult {
    val input = mutableMapOf<String, Any>(
      "type" to type.uppercase(),
      "source" to sourceId,
      "page" to page,
    )
    if (!query.isNullOrBlank()) input["query"] = query
    if (filters.isNotEmpty()) input["filters"] = filters.map { it.toGraphQLInput() }
    val payload =
      graphQL(
        mapOf(
          "query" to
            "mutation(\$input: FetchSourceMangaInput!) { fetchSourceManga(input: \$input) { hasNextPage " +
            "mangas { id title thumbnailUrl author artist description genre status inLibrary } } }",
          "variables" to mapOf("input" to input),
        ),
      )
    val node = payload.path("data").path("fetchSourceManga")
    val mangas = node.path("mangas")
    return SuwayomiBrowseResult(
      mangas = if (!mangas.isArray) emptyList() else mangas.mapNotNull { it.toManga(sourceId) },
      hasNextPage = node.path("hasNextPage").asBoolean(false),
    )
  }

  fun sourcePreferences(sourceId: String): List<SuwayomiPreference> {
    val base = "k: key t: title s: summary en: enabled vis: visible "
    val payload =
      graphQL(
        mapOf(
          "query" to
            "query(\$id: LongString!) { source(id: \$id) { preferences { __typename " +
            "... on CheckBoxPreference { ${base}cvB: currentValue dvB: default } " +
            "... on SwitchPreference { ${base}cvB: currentValue dvB: default } " +
            "... on EditTextPreference { ${base}cvS: currentValue dvS: default } " +
            "... on ListPreference { ${base}cvS: currentValue dvS: default entries entryValues } " +
            "... on MultiSelectListPreference { ${base}cvL: currentValue dvL: default entries entryValues } } } }",
          "variables" to mapOf("id" to sourceId),
        ),
      )
    val nodes = payload.path("data").path("source").path("preferences")
    if (!nodes.isArray) return emptyList()
    return nodes.mapIndexed { index, node ->
      SuwayomiPreference(
        position = index,
        type = node.path("__typename").asText(""),
        key = node.path("k").asText(""),
        title = node.path("t").asText(""),
        summary = node.path("s").takeIf { it.isTextual }?.asText(),
        value = node.firstValue("cvB", "cvS", "cvL"),
        defaultValue = node.firstValue("dvB", "dvS", "dvL"),
        enabled = node.path("en").asBoolean(true),
        visible = node.path("vis").asBoolean(true),
        entries = node.path("entries").stringList(),
        entryValues = node.path("entryValues").stringList(),
      )
    }
  }

  fun updateSourcePreference(
    sourceId: String,
    position: Int,
    change: Map<String, Any>,
  ): JsonNode {
    val payload = LinkedHashMap<String, Any>(change)
    payload["position"] = position
    return graphQL(
      mapOf(
        "query" to
          "mutation(\$source: LongString!, \$change: SourcePreferenceChangeInput!) { " +
          "updateSourcePreference(input: {source: \$source, change: \$change}) { source { id } } }",
        "variables" to mapOf("source" to sourceId, "change" to payload),
      ),
    ).path("data")
  }

  fun extensionSources(pkgName: String): List<SuwayomiSource> {
    val payload =
      graphQL(
        mapOf(
          "query" to
            "query(\$id: String!) { extension(pkgName: \$id) { source { nodes { id name lang isConfigurable } } } }",
          "variables" to mapOf("id" to pkgName),
        ),
      )
    val nodes = payload.path("data").path("extension").path("source").path("nodes")
    if (!nodes.isArray) return emptyList()
    return nodes.mapNotNull { node ->
      val id = node.path("id").asText("")
      if (id.isBlank()) {
        null
      } else {
        SuwayomiSource(
          id = id,
          name = node.path("name").asText(""),
          lang = node.path("lang").takeIf { it.isTextual }?.asText(),
          isConfigurable = node.path("isConfigurable").asBoolean(false),
        )
      }
    }
  }

  fun sourceFilters(sourceId: String): List<SuwayomiFilter> {
    fun block(depth: Int): String =
      "__typename " +
        "... on CheckBoxFilter { k: name dvB: default } " +
        "... on SelectFilter { k: name values dvI: default } " +
        "... on SortFilter { k: name values dvS: default { index ascending } } " +
        "... on TextFilter { k: name dvT: default } " +
        "... on TriStateFilter { k: name dvTri: default } " +
        "... on HeaderFilter { k: name } " +
        "... on SeparatorFilter { k: name } " +
        (if (depth <= 0) "" else "... on GroupFilter { k: name filters { ${block(depth - 1)} } } ")
    val payload =
      graphQL(
        mapOf(
          "query" to "query(\$id: LongString!) { source(id: \$id) { filters { ${block(1)} } } }",
          "variables" to mapOf("id" to sourceId),
        ),
      )
    val nodes = payload.path("data").path("source").path("filters")
    if (!nodes.isArray) return emptyList()
    return nodes.mapIndexed { index, node -> node.toFilter(index) }
  }

  fun manga(mangaId: Long): SuwayomiManga = get("/api/v1/manga/$mangaId")

  fun chapters(mangaId: Long): List<SuwayomiChapter> =
    try {
      getArray("/api/v1/manga/$mangaId/chapters", SuwayomiChapter::class.java)
    } catch (e: SuwayomiException) {
      val raw = e.message.orEmpty()
      if (raw.contains("INTERNAL_SERVER_ERROR")) {
        logger.warn { "Suwayomi failed to list chapters for manga $mangaId: $raw" }
        throw SuwayomiException("图源未返回章节列表：扩展可能已失效，或该漫画在源站已不可用。请更新扩展或更换图源后重试", e)
      }
      throw e
    }

  fun thumbnail(mangaId: Long): ByteArray? =
    try {
      client
        .get()
        .uri(uri("/api/v1/manga/$mangaId/thumbnail"))
        .withAuth()
        .retrieve()
        .body(ByteArray::class.java)
    } catch (e: RestClientResponseException) {
      logger.warn { "Suwayomi thumbnail for manga $mangaId failed: ${e.statusCode}" }
      null
    }

  fun extensionIcon(pkgName: String): org.springframework.http.ResponseEntity<ByteArray>? =
    try {
      client
        .get()
        .uri(uri("/api/v1/extension/icon/$pkgName"))
        .withAuth()
        .retrieve()
        .toEntity(ByteArray::class.java)
    } catch (e: RestClientResponseException) {
      logger.warn { "Suwayomi extension icon for $pkgName failed: ${e.statusCode}" }
      null
    }

  fun page(
    mangaId: Long,
    chapterIndex: Int,
    pageIndex: Int,
  ): SuwayomiPage? =
    try {
      val entity =
        client
          .get()
          .uri(uri("/api/v1/manga/$mangaId/chapter/$chapterIndex/page/$pageIndex"))
          .withAuth("image/*")
          .retrieve()
          .toEntity(ByteArray::class.java)
      val bytes = entity.body
      if (bytes == null || bytes.isEmpty()) {
        null
      } else {
        SuwayomiPage(bytes, entity.headers.contentType?.toString() ?: "image/jpeg")
      }
    } catch (e: RestClientResponseException) {
      if (e.statusCode.value() == 404) {
        null
      } else {
        throw SuwayomiException("Suwayomi page $pageIndex of chapter $chapterIndex responded with ${e.statusCode}", e)
      }
    } catch (e: Exception) {
      throw SuwayomiException("Suwayomi page request failed for chapter $chapterIndex: ${e.message}", e)
    }

  fun fetchChapterPages(chapterId: Long): Int =
    graphQL(
      mapOf(
        "query" to "mutation(\$input: FetchChapterPagesInput!) { fetchChapterPages(input: \$input) { chapter { id pageCount } } }",
        "variables" to mapOf("input" to mapOf("chapterId" to chapterId)),
      ),
    ).path("data").path("fetchChapterPages").path("chapter").path("pageCount").asInt(-1)

  fun enqueueChapterDownload(chapterId: Long) {
    graphQL(
      mapOf(
        "query" to "mutation(\$input: EnqueueChapterDownloadInput!) { enqueueChapterDownload(input: \$input) { downloadStatus { state } } }",
        "variables" to mapOf("input" to mapOf("id" to chapterId)),
      ),
    )
  }

  fun isChapterDownloaded(chapterId: Long): Boolean =
    graphQL(
      mapOf(
        "query" to "query(\$id: Int!) { chapter(id: \$id) { isDownloaded } }",
        "variables" to mapOf("id" to chapterId),
      ),
    ).path("data").path("chapter").path("isDownloaded").asBoolean(false)

  private fun graphQL(payload: Map<String, Any>): JsonNode {
    val response =
      try {
        client
          .post()
          .uri(uri("/api/graphql"))
          .withAuth()
          .contentType(MediaType.APPLICATION_JSON)
          .body(payload)
          .retrieve()
          .body(JsonNode::class.java)
          ?: throw SuwayomiException("Empty GraphQL response for ${payload["query"]}")
      } catch (e: RestClientResponseException) {
        throw SuwayomiException("Suwayomi GraphQL responded with ${e.statusCode}", e)
      } catch (e: SuwayomiException) {
        throw e
      } catch (e: Exception) {
        throw SuwayomiException("Suwayomi GraphQL failed: ${e.message}", e)
      }

    val errors = response.get("errors")
    if (errors != null && errors.isArray && errors.size() > 0) {
      throw SuwayomiException("Suwayomi GraphQL error: ${errors.get(0).path("message").asText()}")
    }
    return response
  }

  private fun graphQLData(payload: Map<String, Any>): JsonNode = graphQL(payload).path("data")

  private fun checkErrors(response: JsonNode): JsonNode {
    val errors = response.get("errors")
    if (errors != null && errors.isArray && errors.size() > 0) {
      throw SuwayomiException("Suwayomi GraphQL error: ${errors.get(0).path("message").asText()}")
    }
    return response
  }

  private inline fun <reified T> get(
    path: String,
    vararg query: Pair<String, String>,
  ): T =
    try {
      client
        .get()
        .uri(uri(path, *query))
        .withAuth()
        .retrieve()
        .body(T::class.java)
        ?: throw SuwayomiException("Empty response for $path")
    } catch (e: RestClientResponseException) {
      throw SuwayomiException("Suwayomi responded with ${e.statusCode} for $path", e)
    } catch (e: SuwayomiException) {
      throw e
    } catch (e: Exception) {
      throw SuwayomiException("Suwayomi request failed for $path: ${e.message}", e)
    }

  private fun <T> getArray(
    path: String,
    elementType: Class<T>,
    vararg query: Pair<String, String>,
  ): List<T> {
    val arrayClass = java.lang.reflect.Array.newInstance(elementType, 0).javaClass as Class<Array<T>>
    return try {
      client
        .get()
        .uri(uri(path, *query))
        .withAuth()
        .retrieve()
        .body(arrayClass)
        ?.toList()
        ?: throw SuwayomiException("Empty response for $path")
    } catch (e: RestClientResponseException) {
      throw SuwayomiException("Suwayomi responded with ${e.statusCode} for $path", e)
    } catch (e: SuwayomiException) {
      throw e
    } catch (e: Exception) {
      throw SuwayomiException("Suwayomi request failed for $path: ${e.message}", e)
    }
  }

  private fun uri(
    path: String,
    vararg query: Pair<String, String>,
  ): URI {
    val builder = UriComponentsBuilder.fromUriString(properties.suwayomiEndpoint.trimEnd('/') + path)
    query.forEach { (key, value) -> builder.queryParam(key, value) }
    return builder.encode().build().toUri()
  }
}

private fun JsonNode.stringList(): List<String> = if (!isArray) emptyList() else map { it.asText() }

private fun JsonNode.firstValue(vararg names: String): JsonNode? = names.firstNotNullOfOrNull { name -> get(name)?.takeUnless { it.isNull } }

private fun JsonNode.toManga(sourceId: String): SuwayomiManga? {
  val id = path("id").asLong(0)
  if (id <= 0) return null
  return SuwayomiManga(
    id = id,
    sourceId = sourceId,
    title = path("title").asText(""),
    thumbnailUrl = path("thumbnailUrl").takeIf { it.isTextual }?.asText(),
    author = path("author").takeIf { it.isTextual }?.asText(),
    artist = path("artist").takeIf { it.isTextual }?.asText(),
    description = path("description").takeIf { it.isTextual }?.asText(),
    genre = path("genre").takeIf { it.isArray }?.map { it.asText() },
    status = path("status").takeIf { it.isTextual }?.asText(),
    inLibrary = path("inLibrary").takeIf { it.isBoolean }?.asBoolean(),
  )
}

private fun JsonNode.toFilter(position: Int): SuwayomiFilter {
  val typename = path("__typename").asText("")
  val children = path("filters")
  return SuwayomiFilter(
    position = position,
    type = typename.removeSuffix("Filter").uppercase(),
    name = path("k").asText(""),
    defaultValue = firstValue("dvB", "dvI", "dvS", "dvT", "dvTri"),
    values = path("values").stringList(),
    children = if (!children.isArray) emptyList() else children.mapIndexed { index, node -> node.toFilter(index) },
  )
}

private fun SuwayomiFilterState.toGraphQLInput(): Map<String, Any> {
  val input = mutableMapOf<String, Any>("position" to position)
  checkBoxState?.let { input["checkBoxState"] = it }
  selectState?.let { input["selectState"] = it }
  if (sortIndex != null) {
    input["sortState"] =
      mapOf(
        "index" to sortIndex,
        "ascending" to (sortAscending ?: true),
      )
  }
  textState?.let { input["textState"] = it }
  triState?.let { input["triState"] = it }
  return input
}
