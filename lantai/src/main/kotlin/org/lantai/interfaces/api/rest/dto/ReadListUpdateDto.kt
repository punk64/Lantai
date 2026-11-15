package org.lantai.interfaces.api.rest.dto

import org.lantai.infrastructure.validation.NullOrNotBlank
import org.lantai.infrastructure.validation.NullOrNotEmpty
import org.hibernate.validator.constraints.UniqueElements

data class ReadListUpdateDto(
  @get:NullOrNotBlank val name: String?,
  val summary: String?,
  @get:NullOrNotEmpty @get:UniqueElements
  val bookIds: List<String>?,
  val ordered: Boolean?,
)
