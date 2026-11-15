package org.lantai.interfaces.api.rest.dto

import org.lantai.domain.model.Author

data class AuthorDto(
  val name: String,
  val role: String,
)

fun Author.toDto() = AuthorDto(name, role)
