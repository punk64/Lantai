package org.lantai.interfaces.api.rest.dto

import jakarta.validation.constraints.NotBlank

data class PasswordUpdateDto(
  @get:NotBlank val password: String,
)
