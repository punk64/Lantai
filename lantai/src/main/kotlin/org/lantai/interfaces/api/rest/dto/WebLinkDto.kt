package org.lantai.interfaces.api.rest.dto

import org.lantai.domain.model.WebLink

data class WebLinkDto(
  val label: String,
  val url: String,
)

fun WebLink.toDto() = WebLinkDto(label, url.toString())
