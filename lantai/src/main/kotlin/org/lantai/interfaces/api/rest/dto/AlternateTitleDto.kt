package org.lantai.interfaces.api.rest.dto

import org.lantai.domain.model.AlternateTitle

data class AlternateTitleDto(
  val label: String,
  val title: String,
)

fun AlternateTitle.toDto() = AlternateTitleDto(label, title)
