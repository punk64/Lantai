package org.lantai.infrastructure.security.apitoken

import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Base64

@Component
class ApiTokenGenerator {
  fun generate(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
  }

  companion object {
    const val PREFIX = "lt_"
    const val HEADER = "X-API-Token"
  }
}
