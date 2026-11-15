package org.lantai.infrastructure.image

enum class ImageType(
  val mediaType: String,
  val imageIOFormat: String,
) {
  PNG("image/png", "PNG"),
  JPEG("image/jpeg", "JPEG"),
}
