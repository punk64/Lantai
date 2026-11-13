package org.lantai.infrastructure.plugin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.User
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders as Requests
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.net.URI

@SpringBootTest
@ActiveProfiles("localdb")
@AutoConfigureMockMvc
class PluginP3VerificationTest(
  @Autowired private val repositories: PluginRepositoryClient,
  @Autowired private val mockMvc: MockMvc,
) {
  @Test
  fun indexParsingAcceptsValidEntries() {
    val entries = repositories.parseIndex(indexJson().toByteArray(), URI.create(BASE_INDEX))

    assertEquals(listOf("com.example.tts", "com.example.caps", "com.example.obj"), entries.map { it.pluginId })
    assertEquals("https://repo.example.com/meta/plugins/tts-1.0.0.zip", entries[0].downloadUrl)
    assertEquals(listOf("tts.speak"), entries[0].capabilities)
    assertEquals("0.6.0", entries[0].minHost)
    assertEquals(listOf("notify.send"), entries[1].capabilities)
    assertEquals("https://cdn.example.com/caps.zip", entries[1].downloadUrl)
    assertEquals(listOf("library.read"), entries[2].capabilities)
    assertTrue(entries.all { it.sha256 == SHA })
  }

  @Test
  fun indexParsingDropsInvalidEntries() {
    val ids = repositories.parseIndex(indexJson().toByteArray(), URI.create(BASE_INDEX)).map { it.pluginId }
    listOf("Bad Id", "com.example.nosha", "com.example.badsha", "com.example.loop", "com.example.ftp", "com.example.proto", "com.example.nourl")
      .forEach { assertTrue(it !in ids, "应被丢弃：$it") }
  }

  @Test
  fun indexParsingRejectsBadDocuments() {
    assertThrows(PluginRequestException::class.java) {
      repositories.parseIndex("""{"schemaVersion":1,"plugins":[]}""".toByteArray(), URI.create(BASE_INDEX))
    }
    assertThrows(PluginRequestException::class.java) {
      repositories.parseIndex("""{"schemaVersion":2}""".toByteArray(), URI.create(BASE_INDEX))
    }
    assertThrows(PluginRequestException::class.java) {
      repositories.parseIndex("not json".toByteArray(), URI.create(BASE_INDEX))
    }
  }

  @Test
  fun repositoryGuardRejectsPrivateAndNonHttpTargets() {
    listOf(
      "http://127.0.0.1:9000/index.json",
      "http://localhost/index.json",
      "http://192.168.1.10/index.json",
      "http://[::1]/index.json",
      "ftp://repo.example.com/index.json",
      "file:///etc/passwd",
      "   ",
    ).forEach { url ->
      assertThrows(PluginRequestException::class.java, { repositories.fetchIndex(url) }, "应被拒绝：$url")
    }
  }

  @Test
  fun repositoryEndpointsRequireAdmin() {
    mockMvc
      .perform(
        Requests
          .post("/api/lantai/v1/plugins/repository/index")
          .contentType(MediaType.APPLICATION_JSON)
          .content("""{"indexUrl":"https://repo.example.com/index.json"}"""),
      ).andExpect(status().isUnauthorized())
    mockMvc
      .perform(
        Requests
          .post("/api/lantai/v1/plugins/repository/install")
          .contentType(MediaType.APPLICATION_JSON)
          .content("""{"indexUrl":"https://repo.example.com/index.json","pluginId":"com.example.tts"}"""),
      ).andExpect(status().isUnauthorized())
  }

  @Test
  fun repositoryEndpointsSurfaceGuardRejection() {
    val caller = User("admin", "", listOf(SimpleGrantedAuthority("ROLE_ADMIN")))
    mockMvc
      .perform(
        Requests
          .post("/api/lantai/v1/plugins/repository/index")
          .contentType(MediaType.APPLICATION_JSON)
          .content("""{"indexUrl":"http://127.0.0.1:9000/index.json"}""")
          .with(user(caller)),
      ).andExpect(status().isBadRequest())
    mockMvc
      .perform(
        Requests
          .post("/api/lantai/v1/plugins/repository/install")
          .contentType(MediaType.APPLICATION_JSON)
          .content("""{"indexUrl":"http://10.0.0.5/index.json","pluginId":"com.example.tts"}""")
          .with(user(caller)),
      ).andExpect(status().isBadRequest())
  }

  private fun indexJson(): String =
    """
    {
      "schemaVersion": 2,
      "plugins": [
        {"id":"com.example.tts","name":"TTS","version":"1.0.0","protocol":2,"minHost":"0.6.0",
         "capabilities":["tts.speak"],"url":"./plugins/tts-1.0.0.zip","sha256":"$SHA"},
        {"id":"com.example.caps","name":"Caps","version":"2.0.0","protocol":2,
         "capabilities":{"notify.send":"/v1/notify"},"url":"https://cdn.example.com/caps.zip","sha256":"$SHA"},
        {"id":"com.example.obj","version":"1.0.0","protocol":2,
         "capabilities":[{"name":"library.read"}],"url":"https://cdn.example.com/obj.zip","sha256":"$SHA"},
        {"id":"Bad Id","version":"1.0.0","protocol":2,"capabilities":[],"url":"https://cdn.example.com/bad.zip","sha256":"$SHA"},
        {"id":"com.example.nosha","version":"1.0.0","protocol":2,"capabilities":[],"url":"https://cdn.example.com/nosha.zip"},
        {"id":"com.example.badsha","version":"1.0.0","protocol":2,"capabilities":[],"url":"https://cdn.example.com/badsha.zip","sha256":"abc"},
        {"id":"com.example.loop","version":"1.0.0","protocol":2,"capabilities":[],"url":"http://127.0.0.1:9000/loop.zip","sha256":"$SHA"},
        {"id":"com.example.ftp","version":"1.0.0","protocol":2,"capabilities":[],"url":"ftp://cdn.example.com/ftp.zip","sha256":"$SHA"},
        {"id":"com.example.proto","version":"1.0.0","protocol":3,"capabilities":[],"url":"https://cdn.example.com/proto.zip","sha256":"$SHA"},
        {"id":"com.example.nourl","version":"1.0.0","protocol":2,"capabilities":[],"sha256":"$SHA"}
      ]
    }
    """.trimIndent()

  private companion object {
    private const val BASE_INDEX = "https://repo.example.com/meta/index.json"
    private const val SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  }
}
