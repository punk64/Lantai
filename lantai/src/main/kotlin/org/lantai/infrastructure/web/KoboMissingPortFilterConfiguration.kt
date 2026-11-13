package org.lantai.infrastructure.web

import jakarta.annotation.PostConstruct
import org.lantai.infrastructure.configuration.LantaiSettingsProvider
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.web.filter.ForwardedHeaderFilter

@Configuration
class KoboMissingPortFilterConfiguration(
  private val lantaiSettingsProvider: LantaiSettingsProvider,
  private val serverSettings: WebServerEffectiveSettings,
  private val forwardedHeaderFilter: FilterRegistrationBean<ForwardedHeaderFilter>?,
) {
  @Bean
  fun koboMissingPortFilter(): FilterRegistrationBean<out KoboMissingPortFilter> =
    FilterRegistrationBean(
      KoboMissingPortFilter { lantaiSettingsProvider.koboPort ?: serverSettings.effectiveServerPort },
    ).apply {
      addUrlPatterns(
        "/kobo/*",
      )
      setName("koboMissingPortFilter")
      order = Ordered.HIGHEST_PRECEDENCE
    }

  @PostConstruct
  fun adjustForwardHeaderFilterOrder() {
    // the ForwardHeaderFilter must be after the KoboMissingPortFilter, as the latter's detection is based on forwarded headers
    // that the former will remove
    forwardedHeaderFilter?.order = Ordered.HIGHEST_PRECEDENCE + 1
  }
}
