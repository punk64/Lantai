package org.lantai.infrastructure.security.oauth2

import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.commons.lang3.RandomStringUtils
import org.lantai.domain.model.LantaiUser
import org.lantai.domain.persistence.LantaiUserRepository
import org.lantai.domain.service.LantaiUserLifecycle
import org.lantai.infrastructure.configuration.LantaiProperties
import org.lantai.infrastructure.security.LantaiPrincipal
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.security.oauth2.core.user.OAuth2User

private val logger = KotlinLogging.logger {}

@Configuration
class LantaiOAuth2UserServiceConfiguration(
  private val userRepository: LantaiUserRepository,
  private val userLifecycle: LantaiUserLifecycle,
  private val lantaiProperties: LantaiProperties,
) {
  @Bean
  fun oauth2UserService(): OAuth2UserService<OAuth2UserRequest, OAuth2User> {
    val defaultDelegate = DefaultOAuth2UserService()
    val githubDelegate = GithubOAuth2UserService()

    return OAuth2UserService { userRequest: OAuth2UserRequest ->
      val delegate =
        when (userRequest.clientRegistration.registrationId.lowercase()) {
          "github" -> githubDelegate
          else -> defaultDelegate
        }

      val oAuth2User = delegate.loadUser(userRequest)

      val email =
        oAuth2User.getAttribute<String>("email")
          ?: throw OAuth2AuthenticationException("ERR_1024")

      val existingUser =
        userRepository.findByEmailIgnoreCaseOrNull(email)
          ?: tryCreateNewUser(email)

      LantaiPrincipal(existingUser, oAuth2User = oAuth2User)
    }
  }

  @Bean
  fun oidcUserService(): OAuth2UserService<OidcUserRequest, OidcUser> {
    val delegate = OidcUserService()
    return OAuth2UserService { userRequest: OidcUserRequest ->
      val oidcUser = delegate.loadUser(userRequest)

      if (oidcUser.email == null) throw OAuth2AuthenticationException("ERR_1028")
      if (lantaiProperties.oidcEmailVerification && oidcUser.emailVerified == null) throw OAuth2AuthenticationException("ERR_1027")
      if (lantaiProperties.oidcEmailVerification && oidcUser.emailVerified == false) throw OAuth2AuthenticationException("ERR_1026")

      val existingUser =
        userRepository.findByEmailIgnoreCaseOrNull(oidcUser.email)
          ?: tryCreateNewUser(oidcUser.email)

      LantaiPrincipal(existingUser, oidcUser)
    }
  }

  private fun tryCreateNewUser(email: String) =
    if (lantaiProperties.oauth2AccountCreation) {
      logger.info { "Creating new user from OAuth2 login: $email" }
      userLifecycle.createUser(LantaiUser(email, RandomStringUtils.secure().nextAlphanumeric(12)))
    } else {
      throw OAuth2AuthenticationException("ERR_1025")
    }
}
