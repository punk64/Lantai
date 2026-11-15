package org.lantai.infrastructure.security

import org.lantai.infrastructure.configuration.LantaiProperties
import org.springframework.boot.autoconfigure.condition.ConditionOutcome
import org.springframework.boot.autoconfigure.condition.SpringBootCondition
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertyName
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.core.type.AnnotatedTypeMetadata
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import java.util.Collections

@Configuration
class CorsConfiguration {
  @Bean
  @Conditional(CorsAllowedOriginsPresent::class)
  fun corsConfigurationSource(
    sessionHeaderName: String,
    lantaiProperties: LantaiProperties,
  ): UrlBasedCorsConfigurationSource =
    UrlBasedCorsConfigurationSource().apply {
      registerCorsConfiguration(
        "/**",
        CorsConfiguration().applyPermitDefaultValues().apply {
          allowedOrigins = lantaiProperties.cors.allowedOrigins
          allowedMethods = HttpMethod.values().map { it.name() }
          allowCredentials = true
          addExposedHeader(HttpHeaders.CONTENT_DISPOSITION)
          addExposedHeader(sessionHeaderName)
        },
      )
    }

  class CorsAllowedOriginsPresent : SpringBootCondition() {
    override fun getMatchOutcome(
      context: ConditionContext,
      metadata: AnnotatedTypeMetadata,
    ): ConditionOutcome {
      val defined =
        Binder
          .get(context.environment)
          .bind(ConfigurationPropertyName.of("lantai.cors.allowed-origins"), Bindable.of(List::class.java))
          .orElse(Collections.emptyList<String>())
          .isNotEmpty()
      return ConditionOutcome(defined, "Cors allowed-origins present")
    }
  }
}
