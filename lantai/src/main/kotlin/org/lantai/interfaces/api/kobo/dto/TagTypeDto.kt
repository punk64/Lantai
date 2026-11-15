package org.lantai.interfaces.api.kobo.dto

import com.fasterxml.jackson.annotation.JsonProperty

enum class TagTypeDto {
  @JsonProperty("SystemTag")
  SYSTEM_TAG,

  @JsonProperty("UserTag")
  USER_TAG,
}
