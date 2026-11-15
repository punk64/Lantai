package org.lantai.infrastructure.security.apitoken

import org.lantai.infrastructure.jooq.main.ServerSettingsDao
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@Component
class TokenCipher(
  private val settings: ServerSettingsDao,
) {
  private val secret: ByteArray by lazy {
    settings.getSettingByKey(KEY_NAME, String::class.java)?.let { Base64.getDecoder().decode(it) }
      ?: ByteArray(KEY_SIZE).also { SecureRandom().nextBytes(it) }.also {
        settings.saveSetting(KEY_NAME, Base64.getEncoder().encodeToString(it))
      }
  }

  fun encrypt(raw: String): String {
    if (isEncrypted(raw)) return raw
    val iv = ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(secret, ALGORITHM), GCMParameterSpec(TAG_BITS, iv))
    val body = cipher.doFinal(raw.toByteArray(Charsets.UTF_8))
    return PREFIX + Base64.getEncoder().encodeToString(iv + body)
  }

  fun decrypt(stored: String): String? {
    if (!isEncrypted(stored)) return null
    return runCatching {
      val all = Base64.getDecoder().decode(stored.removePrefix(PREFIX))
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(secret, ALGORITHM), GCMParameterSpec(TAG_BITS, all.copyOfRange(0, IV_SIZE)))
      String(cipher.doFinal(all.copyOfRange(IV_SIZE, all.size)), Charsets.UTF_8)
    }.getOrNull()
  }

  fun isEncrypted(stored: String): Boolean = stored.startsWith(PREFIX)

  companion object {
    private const val KEY_NAME = "API_TOKEN_ENCRYPTION_KEY"
    private const val PREFIX = "enc:"
    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_SIZE = 32
    private const val IV_SIZE = 12
    private const val TAG_BITS = 128
  }
}
