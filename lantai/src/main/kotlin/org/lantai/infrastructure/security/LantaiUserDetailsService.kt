package org.lantai.infrastructure.security

import org.lantai.domain.persistence.LantaiUserRepository
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Component

@Component
class LantaiUserDetailsService(
  private val userRepository: LantaiUserRepository,
) : UserDetailsService {
  override fun loadUserByUsername(username: String): UserDetails =
    userRepository.findByLoginOrNull(username)?.let {
      LantaiPrincipal(it)
    } ?: throw UsernameNotFoundException(username)
}
