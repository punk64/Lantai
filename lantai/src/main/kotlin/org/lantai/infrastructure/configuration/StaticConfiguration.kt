package org.lantai.infrastructure.configuration

import org.lantai.infrastructure.image.ImageType
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class StaticConfiguration {
  @Bean("thumbnailType")
  fun thumbnailType() = ImageType.JPEG

  @Bean("pdfImageType")
  fun pdfImageType() = ImageType.JPEG

  @Bean("pdfResolution")
  fun pdfResolution(): Float = 3200F
}
