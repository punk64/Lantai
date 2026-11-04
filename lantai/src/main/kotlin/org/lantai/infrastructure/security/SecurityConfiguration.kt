package org.lantai.infrastructure.security

import jakarta.servlet.Filter
import org.lantai.domain.model.UserRoles
import org.lantai.domain.persistence.ApiTokenRepository
import org.lantai.domain.persistence.LantaiUserRepository
import org.lantai.infrastructure.configuration.LantaiSettingsProvider
import org.lantai.infrastructure.hash.Hasher
import org.lantai.infrastructure.security.agent.AgentTokenAuthenticationFilter
import org.lantai.infrastructure.security.agent.AgentTokenProvider
import org.lantai.infrastructure.security.apitoken.ApiTokenAuthenticationFilter
import org.lantai.infrastructure.security.apitoken.ApiTokenScopePolicy
import org.lantai.infrastructure.security.apikey.ApiKeyAuthenticationFilter
import org.lantai.infrastructure.security.apikey.ApiKeyAuthenticationProvider
import org.lantai.infrastructure.security.apikey.HeaderApiKeyAuthenticationConverter
import org.lantai.infrastructure.security.apikey.UriRegexApiKeyAuthenticationConverter
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest
import org.springframework.boot.actuate.health.HealthEndpoint
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.security.authentication.AuthenticationEventPublisher
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.ProviderManager
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.session.SessionRegistry
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource
import org.springframework.security.web.authentication.rememberme.TokenBasedRememberMeServices
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
class SecurityConfiguration(
  private val lantaiSettingsProvider: LantaiSettingsProvider,
  private val lantaiUserDetailsService: UserDetailsService,
  private val apiKeyAuthenticationProvider: ApiKeyAuthenticationProvider,
  private val oauth2UserService: OAuth2UserService<OAuth2UserRequest, OAuth2User>,
  private val oidcUserService: OAuth2UserService<OidcUserRequest, OidcUser>,
  private val sessionCookieName: String,
  private val userAgentWebAuthenticationDetailsSource: WebAuthenticationDetailsSource,
  private val theSessionRegistry: SessionRegistry,
  private val opdsAuthenticationEntryPoint: OpdsAuthenticationEntryPoint,
  private val lantaiAuthenticationEntryPoint: LantaiAuthenticationEntryPoint,
  private val authenticationEventPublisher: AuthenticationEventPublisher,
  private val tokenEncoder: TokenEncoder,
  private val hasher: Hasher,
  private val agentTokenProvider: AgentTokenProvider,
  apiTokenRepository: ApiTokenRepository,
  lantaiUserRepository: LantaiUserRepository,
  apiTokenScopePolicy: ApiTokenScopePolicy,
  clientRegistrationRepository: InMemoryClientRegistrationRepository?,
) {
  private val oauth2Enabled = clientRegistrationRepository != null

  private val apiTokenAuthenticationFilter =
    ApiTokenAuthenticationFilter(apiTokenRepository, lantaiUserRepository, apiTokenScopePolicy)

  @Order(0)
  @Bean
  fun bulkMetadataFilterChain(http: HttpSecurity): SecurityFilterChain {
    http {
      cors {}

      csrf { disable() }
      formLogin { disable() }
      httpBasic { disable() }
      logout { disable() }

      securityMatcher("/api/lantai/v1/bulk/**")
      authorizeHttpRequests {
        authorize(anyRequest, hasRole(UserRoles.BULK_METADATA.name))
      }

      headers {
        cacheControl { disable() }
      }

      addFilterBefore<AnonymousAuthenticationFilter>(apiTokenAuthenticationFilter)
      addFilterBefore<AnonymousAuthenticationFilter>(AgentTokenAuthenticationFilter(agentTokenProvider))
    }

    return http.build()
  }

  @Order(1)
  @Bean
  fun filterChain(http: HttpSecurity): SecurityFilterChain {
    http
      .cors {}
      .csrf { it.disable() }
      .securityMatchers {
        // only apply security to those endpoints
        it.requestMatchers(
          "/api/**",
          "/opds/**",
          "/sse/**",
          "/oauth2/authorization/**",
          "/login/oauth2/code/**",
          "/v3/api-docs/**",
          "/swagger-ui/**",
          "/swagger-ui.html",
        )
        it.requestMatchers(EndpointRequest.toAnyEndpoint())
      }.authorizeHttpRequests {
        // allow unauthorized access to actuator health endpoint
        // this will only show limited details as `management.endpoint.health.show-details` is set to `when-authorized`
        it.requestMatchers(EndpointRequest.to(HealthEndpoint::class.java)).permitAll()
        // restrict all other actuator endpoints to ADMIN only
        it.requestMatchers(EndpointRequest.toAnyEndpoint()).hasRole(UserRoles.ADMIN.name)

        it
          .requestMatchers(
            // used by webui
            "/api/v1/oauth2/providers",
            // used by webui, we check for authorization within the controller method directly and filter results from there
            "/api/v1/client-settings/global/list",
            // epub resources - fonts are always requested anonymously, so we check for authorization within the controller method directly
            "/api/v1/books/{bookId}/resource/**",
            // dynamic fonts
            "/api/v1/fonts/resource/**",
            // OPDS authentication document
            "/opds/v2/auth",
            // KOReader user creation
            "/koreader/users/create",
          ).permitAll()

        it
          .requestMatchers(
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
          ).hasRole(UserRoles.ADMIN.name)

        // all other endpoints are restricted to authenticated users
        it
          .requestMatchers(
            "/api/**",
            "/opds/**",
            "/sse/**",
          ).authenticated()
      }.headers { headersConfigurer ->
        headersConfigurer.cacheControl { it.disable() } // headers are set in WebMvcConfiguration
        headersConfigurer.frameOptions { it.sameOrigin() } // for epubreader iframes
      }.userDetailsService(lantaiUserDetailsService)
      .httpBasic {
        it.authenticationDetailsSource(userAgentWebAuthenticationDetailsSource)
        it.authenticationEntryPoint(lantaiAuthenticationEntryPoint)
      }.logout {
        it.logoutUrl("/api/logout")
        it.deleteCookies(sessionCookieName)
        it.invalidateHttpSession(true)
      }.sessionManagement { session ->
        session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
        session.sessionConcurrency {
          it.sessionRegistry(theSessionRegistry)
          it.maximumSessions(-1)
        }
      }.exceptionHandling {
        it.defaultAuthenticationEntryPointFor(opdsAuthenticationEntryPoint, PathPatternRequestMatcher.withDefaults().matcher("/opds/v2/**"))
      }

    if (oauth2Enabled) {
      http.oauth2Login { oauth2 ->
        oauth2.userInfoEndpoint {
          it.userService(oauth2UserService)
          it.oidcUserService(oidcUserService)
        }
        oauth2.authenticationDetailsSource(userAgentWebAuthenticationDetailsSource)
        oauth2
          .loginPage("/login")
          .defaultSuccessUrl("/?server_redirect=Y", true)
          .failureHandler { request, response, exception ->
            val errorMessage =
              when (exception) {
                is OAuth2AuthenticationException -> exception.error.errorCode
                else -> exception.message
              }
            val url = "/login?server_redirect=Y&error=$errorMessage"
            SimpleUrlAuthenticationFailureHandler(url).onAuthenticationFailure(request, response, exception)
          }
        oauth2.redirectionEndpoint {
        }
      }
    }

    http
      .rememberMe {
        it.rememberMeServices(
          TokenBasedRememberMeServices(lantaiSettingsProvider.rememberMeKey, lantaiUserDetailsService).apply {
            setTokenValiditySeconds(lantaiSettingsProvider.rememberMeDuration.inWholeSeconds.toInt())
            setAuthenticationDetailsSource(userAgentWebAuthenticationDetailsSource)
            setCookieName("lantai-remember-me")
          },
        )
      }

    http.addFilterBefore(apiTokenAuthenticationFilter, BasicAuthenticationFilter::class.java)
    http.addFilterAfter(restAuthenticationFilter(), BasicAuthenticationFilter::class.java)

    return http.build()
  }

  @Bean
  fun koboFilterChain(
    http: HttpSecurity,
  ): SecurityFilterChain {
    http {
      cors {}

      csrf { disable() }
      formLogin { disable() }
      httpBasic { disable() }
      logout { disable() }

      securityMatcher("/kobo/**")
      authorizeHttpRequests {
        authorize(anyRequest, hasRole(UserRoles.KOBO_SYNC.name))
      }

      headers {
        cacheControl { disable() }
      }

      addFilterBefore<AnonymousAuthenticationFilter>(apiTokenAuthenticationFilter)
      addFilterBefore<AnonymousAuthenticationFilter>(koboAuthenticationFilter())
    }

    return http.build()
  }

  @Bean
  fun kosyncFilterChain(
    http: HttpSecurity,
  ): SecurityFilterChain {
    http {
      cors {}

      csrf { disable() }
      formLogin { disable() }
      httpBasic { disable() }
      logout { disable() }

      securityMatcher("/koreader/**")
      authorizeHttpRequests {
        authorize(anyRequest, hasRole(UserRoles.KOREADER_SYNC.name))
      }

      headers {
        cacheControl { disable() }
      }

      addFilterBefore<AnonymousAuthenticationFilter>(apiTokenAuthenticationFilter)
      addFilterBefore<AnonymousAuthenticationFilter>(kosyncAuthenticationFilter())
    }

    return http.build()
  }

  fun koboAuthenticationFilter(): Filter =
    ApiKeyAuthenticationFilter(
      apiKeyAuthenticationProvider(),
      UriRegexApiKeyAuthenticationConverter(Regex("""/kobo/([\w-]+)"""), hasher, tokenEncoder, userAgentWebAuthenticationDetailsSource),
    )

  fun kosyncAuthenticationFilter(): Filter =
    ApiKeyAuthenticationFilter(
      apiKeyAuthenticationProvider(),
      HeaderApiKeyAuthenticationConverter("X-Auth-User", hasher, tokenEncoder, userAgentWebAuthenticationDetailsSource),
    )

  fun restAuthenticationFilter(): Filter =
    ApiKeyAuthenticationFilter(
      apiKeyAuthenticationProvider(),
      HeaderApiKeyAuthenticationConverter("X-API-Key", hasher, tokenEncoder, userAgentWebAuthenticationDetailsSource),
    )

  fun apiKeyAuthenticationProvider(): AuthenticationManager =
    ProviderManager(apiKeyAuthenticationProvider).apply {
      setAuthenticationEventPublisher(authenticationEventPublisher)
    }
}
