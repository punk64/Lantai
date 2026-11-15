package org.lantai.domain.model

class PageHashUnknown(
  hash: String,
  size: Long? = null,
  val matchCount: Int = 0,
) : PageHash(hash, size)
