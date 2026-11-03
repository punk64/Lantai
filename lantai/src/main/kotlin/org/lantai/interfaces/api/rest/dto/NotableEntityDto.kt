package org.lantai.interfaces.api.rest.dto

import org.lantai.domain.model.NotableEntity

data class NotableEntityDto(
  val kind: String,
  val name: String,
)

fun NotableEntity.toDto() = NotableEntityDto(kind, name)
