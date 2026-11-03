package org.lantai.infrastructure.online

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.JsonNode

@JsonIgnoreProperties(ignoreUnknown = true)
data class SuwayomiSource(
  val id: String = "",
  val name: String = "",
  val lang: String? = null,
  val baseUrl: String? = null,
  val isConfigurable: Boolean = false,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SuwayomiManga(
  val id: Long = 0,
  val sourceId: String? = null,
  val title: String = "",
  val thumbnailUrl: String? = null,
  val author: String? = null,
  val artist: String? = null,
  val description: String? = null,
  val genre: List<String>? = null,
  val status: String? = null,
  val realUrl: String? = null,
  val inLibrary: Boolean? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SuwayomiChapter(
  val id: Long = 0,
  val name: String = "",
  val index: Int = 0,
  val chapterNumber: Double? = null,
  val pageCount: Int = -1,
  val downloaded: Boolean? = null,
  val uploadDate: Long = 0,
  val scanlator: String? = null,
  val realUrl: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SuwayomiSearchResult(
  val mangaList: List<SuwayomiManga>? = null,
  val hasNextPage: Boolean? = null,
)

data class SuwayomiBrowseResult(
  val mangas: List<SuwayomiManga>,
  val hasNextPage: Boolean = false,
)

data class SuwayomiPreference(
  val position: Int,
  val type: String,
  val key: String,
  val title: String,
  val summary: String? = null,
  val value: JsonNode? = null,
  val defaultValue: JsonNode? = null,
  val enabled: Boolean = true,
  val visible: Boolean = true,
  val entries: List<String> = emptyList(),
  val entryValues: List<String> = emptyList(),
)

data class SuwayomiFilter(
  val position: Int,
  val type: String,
  val name: String,
  val defaultValue: JsonNode? = null,
  val values: List<String> = emptyList(),
  val children: List<SuwayomiFilter> = emptyList(),
)

data class SuwayomiFilterState(
  val position: Int,
  val checkBoxState: Boolean? = null,
  val selectState: Int? = null,
  val sortIndex: Int? = null,
  val sortAscending: Boolean? = null,
  val textState: String? = null,
  val triState: String? = null,
)

class SuwayomiPage(
  val bytes: ByteArray,
  val mediaType: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SuwayomiAbout(
  val name: String? = null,
  val version: String? = null,
  val revision: String? = null,
  val buildType: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SuwayomiExtension(
  val pkgName: String = "",
  val name: String? = null,
  val versionName: String? = null,
  val lang: String? = null,
  val isNsfw: Boolean = false,
  val isInstalled: Boolean = false,
  val hasUpdate: Boolean = false,
  val isObsolete: Boolean = false,
  val iconUrl: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SuwayomiExtensionStore(
  val name: String? = null,
  val indexUrl: String? = null,
  val extensionListUrl: String? = null,
  val isLegacy: Boolean = false,
)

data class SuwayomiCatalogEntry(
  val pkgName: String,
  val name: String,
  val versionName: String,
  val lang: String,
  val versionCode: Long,
  val jarUrl: String?,
  val apkUrl: String?,
  val iconUrl: String?,
)

class SuwayomiException(
  message: String,
  cause: Throwable? = null,
) : RuntimeException(message, cause)
