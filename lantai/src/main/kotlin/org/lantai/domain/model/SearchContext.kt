package org.lantai.domain.model

class SearchContext private constructor(
  val userId: String?,
  val restrictions: ContentRestrictions,
  val libraryIds: Collection<String>?,
  val readerView: Boolean,
) {
  constructor(user: LantaiUser?, readerView: Boolean = false) : this(
    user?.id,
    user?.restrictions ?: ContentRestrictions(),
    user?.getAuthorizedLibraryIds(null),
    readerView,
  )

  fun readerOnly(): SearchContext = SearchContext(userId, restrictions, libraryIds, true)

  companion object {
    fun empty() = SearchContext(null, ContentRestrictions(), null, false)

    fun ofAnonymousUser() = SearchContext("UNUSED", ContentRestrictions(), null, false)
  }
}
