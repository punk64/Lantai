package org.lantai.interfaces.api.rest.dto

import jakarta.validation.constraints.NotBlank
import org.lantai.domain.model.PageHashKnown

data class PageHashCreationDto(
  @get:NotBlank val hash: String,
  val size: Long? = null,
  val action: PageHashKnown.Action,
)
