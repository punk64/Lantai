package org.lantai.infrastructure.online

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.JsonNode

@JsonIgnoreProperties(ignoreUnknown = true)
data class RemoteSource(
  val id: String = "",
  val name: String = "",
  val lang: String? = null,
  val baseUrl: String? = null,
  val isConfigurable: Boolean = false,
  val contentType: String? = null,
  val contentFormat: String? = null,
)

data class RemoteChapterContent(
  val format: String = "html",
  val content: String = "",
  val chars: Int = 0,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RemoteManga(
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
data class RemoteChapter(
  val id: Long = 0,
  val name: String = "",
  val index: Int = 0,
  val chapterNumber: Double? = null,
  val pageCount: Int = -1,
  val downloaded: Boolean? = null,
  val uploadDate: Long = 0,
  val scanlator: String? = null,
  val realUrl: String? = null,
  val contentType: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RemoteSearchResult(
  val mangaList: List<RemoteManga>? = null,
  val hasNextPage: Boolean? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RemoteBrowseResult(
  val mangas: List<RemoteManga> = emptyList(),
  val hasNextPage: Boolean = false,
)

data class OnlinePreference(
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

@JsonIgnoreProperties(ignoreUnknown = true)
data class OnlineFilter(
  val position: Int,
  val type: String,
  val name: String,
  val defaultValue: JsonNode? = null,
  val values: List<String> = emptyList(),
  val children: List<OnlineFilter> = emptyList(),
)

data class OnlineFilterState(
  val position: Int,
  val checkBoxState: Boolean? = null,
  val selectState: Int? = null,
  val sortIndex: Int? = null,
  val sortAscending: Boolean? = null,
  val textState: String? = null,
  val triState: String? = null,
)

class RemotePage(
  val bytes: ByteArray,
  val mediaType: String,
)
