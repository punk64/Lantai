package org.lantai.domain.model

class SearchContext private constructor(
  val userId: String?,
  val restrictions: ContentRestrictions,
  val libraryIds: Collection<String>?,
) {
  constructor(user: LantaiUser?) : this(user?.id, user?.restrictions ?: ContentRestrictions(), user?.getAuthorizedLibraryIds(null))

  companion object {
    fun empty() = SearchContext(null)

    fun ofAnonymousUser() = SearchContext("UNUSED", ContentRestrictions(), null)
  }
}
