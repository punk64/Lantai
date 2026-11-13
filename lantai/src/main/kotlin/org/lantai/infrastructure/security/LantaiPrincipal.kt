package org.lantai.infrastructure.security

import org.lantai.domain.model.ApiKey
import org.lantai.domain.model.LantaiUser
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.oauth2.core.oidc.OidcIdToken
import org.springframework.security.oauth2.core.oidc.OidcUserInfo
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.security.oauth2.core.user.OAuth2User
import java.io.Serial
import java.io.Serializable

class LantaiPrincipal(
  val user: LantaiUser,
  @Transient
  val oAuth2User: OAuth2User? = null,
  @Transient
  val oidcUser: OidcUser? = null,
  val apiKey: ApiKey? = null,
  private val name: String = user.username,
  private val authorityRoles: Set<String>? = null,
) : UserDetails,
  OAuth2User,
  OidcUser,
  Serializable {
  override fun getAuthorities(): MutableCollection<out GrantedAuthority> =
    (authorityRoles ?: user.roles.map { it.name })
      .map { SimpleGrantedAuthority("ROLE_$it") }
      .toMutableSet()

  override fun isEnabled() = user.enabled

  override fun getUsername() = name

  override fun isCredentialsNonExpired() = true

  override fun getPassword() = user.password

  override fun isAccountNonExpired() = true

  override fun isAccountNonLocked() = true

  override fun getName() = name

  override fun getAttributes(): MutableMap<String, Any> = oAuth2User?.attributes ?: mutableMapOf()

  override fun getClaims(): MutableMap<String, Any> = oidcUser?.claims ?: mutableMapOf()

  override fun getUserInfo(): OidcUserInfo? = oidcUser?.userInfo

  override fun getIdToken(): OidcIdToken? = oidcUser?.idToken

  companion object {
    @Serial
    private const val serialVersionUID: Long = -1115127032708270860L
  }
}
