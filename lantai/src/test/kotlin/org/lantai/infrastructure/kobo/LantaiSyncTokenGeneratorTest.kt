package org.lantai.infrastructure.kobo

import org.assertj.core.api.Assertions.assertThat
import org.lantai.domain.model.LantaiSyncToken
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.util.Base64

@SpringBootTest
class LantaiSyncTokenGeneratorTest(
  @Autowired private val tokenGenerator: LantaiSyncTokenGenerator,
) {
  private val base64Encoder by lazy { Base64.getEncoder().withoutPadding() }

  fun encodeToBase64(token: String): String = base64Encoder.encodeToString(token.toByteArray())

  @Test
  fun `given Kobo store token when getting token then it contains the kobo store token`() {
    // given
    val koboToken = "fake.token"

    // when
    val lantaiToken = tokenGenerator.fromBase64(koboToken)

    // then
    assertThat(lantaiToken.rawKoboSyncToken).isEqualTo(koboToken)
  }

  @Test
  fun `given calibre web token when getting token then it contains the kobo store token`() {
    // given
    val calibreWebToken =
      """
      {
        "data": {
          "archive_last_modified": -62135596800.0,
          "books_last_created": -62135596800.0,
          "books_last_modified": -62135596800.0,
          "raw_kobo_store_token": "fake.token",
          "reading_state_last_modified": -62135596800.0,
          "tags_last_modified": -62135596800.0
        },
        "version": "1-1-0"
      }
      """.trimIndent()

    // when
    val lantaiToken = tokenGenerator.fromBase64(encodeToBase64(calibreWebToken))

    // then
    assertThat(lantaiToken.rawKoboSyncToken).isEqualTo("fake.token")
  }

  @Test
  fun `given Lantai token when getting token then it contains the kobo store token`() {
    // given
    val lantaiToken = LantaiSyncToken(rawKoboSyncToken = "fake.token")

    // when
    val encodedToken = tokenGenerator.toBase64(lantaiToken)
    val decodedToken = tokenGenerator.fromBase64(encodedToken)

    // then
    assertThat(decodedToken.rawKoboSyncToken).isEqualTo("fake.token")
    assertThat(encodedToken).startsWith("LANTAI.")
  }

  @Test
  fun `given unidentified token when getting token then it is empty`() {
    // given
    val token = "unrecognized"

    // when
    val lantaiToken = tokenGenerator.fromBase64(token)

    // then
    assertThat(lantaiToken.rawKoboSyncToken).isEmpty()
  }
}
