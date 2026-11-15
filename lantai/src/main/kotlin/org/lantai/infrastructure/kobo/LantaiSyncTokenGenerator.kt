package org.lantai.infrastructure.kobo

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.http.HttpServletRequest
import org.lantai.domain.model.LantaiSyncToken
import org.lantai.infrastructure.kobo.KoboHeaders.X_KOBO_SYNCTOKEN
import org.springframework.stereotype.Component
import java.util.Base64

private val logger = KotlinLogging.logger {}

private const val LANTAI_TOKEN_PREFIX = "LANTAI."

@Component
class LantaiSyncTokenGenerator(
  private val objectMapper: ObjectMapper,
) {
  private val base64Encoder by lazy { Base64.getEncoder().withoutPadding() }
  private val base64Decoder by lazy { Base64.getDecoder() }

  /**
   * Convert any SyncToken to a [LantaiSyncToken].
   * The input SyncToken type depends on the String format:
   * - the official Kobo store token is of the form `base64.base64`
   * - the Calibre Web token is a single base64 string
   * - the Lantai token is a base64 string prefixed by `LANTAI.`
   */
  fun fromBase64(base64Token: String): LantaiSyncToken {
    try {
      // check for a Lantai token
      if (base64Token.startsWith(LANTAI_TOKEN_PREFIX)) {
        return objectMapper.readValue(base64Decoder.decode(base64Token.removePrefix(LANTAI_TOKEN_PREFIX)))
      }

      // check for a Calibre Web token
      if (!base64Token.contains('.')) {
        try {
          val json = objectMapper.readTree(base64Decoder.decode(base64Token))
          val koboToken = json.get("data").get("raw_kobo_store_token").asText()
          return LantaiSyncToken(rawKoboSyncToken = koboToken)
        } catch (e: Exception) {
          logger.warn { "Failed to parse potential CalibreWeb token" }
        }
      }

      // check for a Kobo store token
      if (base64Token.contains('.')) {
        return LantaiSyncToken(rawKoboSyncToken = base64Token)
      }
    } catch (_: Exception) {
    }

    // in last resort return a default token
    return LantaiSyncToken()
  }

  fun toBase64(token: LantaiSyncToken): String = LANTAI_TOKEN_PREFIX + base64Encoder.encodeToString(objectMapper.writeValueAsString(token).toByteArray())

  fun fromRequestHeaders(request: HttpServletRequest): LantaiSyncToken? {
    val syncTokenB64 = request.getHeader(X_KOBO_SYNCTOKEN)
    return if (syncTokenB64 != null) fromBase64(syncTokenB64) else null
  }
}
