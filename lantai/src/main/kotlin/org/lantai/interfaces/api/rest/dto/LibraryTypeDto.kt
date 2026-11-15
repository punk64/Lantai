package org.lantai.interfaces.api.rest.dto

import org.lantai.domain.model.Library

enum class LibraryTypeDto {
  LOCAL,
  ONLINE,
}

fun Library.Type.toDto() =
  when (this) {
    Library.Type.LOCAL -> LibraryTypeDto.LOCAL
    Library.Type.ONLINE -> LibraryTypeDto.ONLINE
  }
