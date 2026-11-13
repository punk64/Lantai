package org.lantai.interfaces.api.rest.dto

import org.lantai.domain.model.R2Locator

data class R2Positions(
  val total: Int,
  val positions: List<R2Locator>,
)
