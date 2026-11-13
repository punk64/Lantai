package org.lantai.infrastructure.plugin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.lantai.domain.model.ApiToken
import org.lantai.domain.model.LantaiUser
import org.lantai.domain.model.UserRoles
import org.lantai.domain.persistence.ApiTokenRepository
import org.lantai.domain.persistence.LantaiUserRepository
import org.lantai.domain.persistence.PluginStore
import org.lantai.infrastructure.security.apitoken.ApiTokenGenerator
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders as Requests
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("localdb")
class PluginP2VerificationTest(
  @Autowired private val mockMvc: MockMvc,
  @Autowired private val store: PluginStore,
  @Autowired private val apiTokens: ApiTokenRepository,
  @Autowired private val users: LantaiUserRepository,
  @Autowired private val broker: PluginEventBroker,
) {
  private val mapper = ObjectMapper()

  @AfterEach
  fun tidy() {
    cleanup()
  }

  @Test
  fun keyValueQuotaAndIsolation() {
    cleanup()
    val token = pluginToken("test.p2", setOf(ApiToken.Scope.STORAGE_BLOB, ApiToken.Scope.EVENT_OBSERVE))
    val plain = pluginToken("test.plain", setOf(ApiToken.Scope.EVENT_OBSERVE))

    val written = perform(Requests.put("/api/lantai/v1/plugin/kv/demo"), token, """{"value":"hello"}""")
    assertEquals(200, written.response.status)
    assertEquals(5L, json(written).path("size").asLong())

    val read = perform(Requests.get("/api/lantai/v1/plugin/kv/demo"), token, null)
    assertEquals(200, read.response.status)
    assertEquals("hello", json(read).path("value").asText())

    val listed = perform(Requests.get("/api/lantai/v1/plugin/kv"), token, null)
    assertEquals(200, listed.response.status)
    assertEquals(5L, json(listed).path("usage").asLong())
    assertEquals(1, json(listed).path("total").asInt())
    assertEquals(1, json(listed).path("items").size())

    val overwritten = perform(Requests.put("/api/lantai/v1/plugin/kv/demo"), token, """{"value":"hello world"}""")
    assertEquals(11L, json(overwritten).path("size").asLong())
    assertEquals(11L, json(perform(Requests.get("/api/lantai/v1/plugin/kv"), token, null)).path("usage").asLong())

    assertEquals(204, perform(Requests.delete("/api/lantai/v1/plugin/kv/demo"), token, null).response.status)
    assertEquals(404, perform(Requests.get("/api/lantai/v1/plugin/kv/demo"), token, null).response.status)
    assertEquals(0L, json(perform(Requests.get("/api/lantai/v1/plugin/kv"), token, null)).path("usage").asLong())

    val oversized = "x".repeat((PluginKvService.MAX_VALUE_BYTES + 1).toInt())
    assertEquals(413, perform(Requests.put("/api/lantai/v1/plugin/kv/big"), token, mapper.writeValueAsString(mapOf("value" to oversized))).response.status)

    val longKey = "k".repeat(PluginKvService.MAX_KEY_LENGTH + 1)
    assertEquals(400, perform(Requests.put("/api/lantai/v1/plugin/kv/$longKey"), token, """{"value":"x"}""").response.status)

    assertEquals(403, perform(Requests.get("/api/lantai/v1/plugin/kv"), plain, null).response.status)

    val manual = ApiToken(name = MANUAL_TOKEN, ownerId = adminUser().id, tokenValue = "lt_manual_p2", scopes = setOf(ApiToken.Scope.STORAGE_BLOB))
    apiTokens.insert(manual)
    assertEquals(403, perform(Requests.get("/api/lantai/v1/plugin/kv"), manual.tokenValue, null).response.status)

    mockMvc.perform(Requests.get("/api/lantai/v1/plugin/events")).andExpect(status().isUnauthorized())

    mockMvc
      .perform(Requests.get("/api/lantai/v1/plugin/events").header(ApiTokenGenerator.HEADER, token))
      .andExpect(status().isOk())
      .andExpect(request().asyncStarted())

    store.listKv("test.p2", 0, 500).forEach { store.deleteKv("test.p2", it.key) }
    assertEquals(0L, store.kvTotalBytes("test.p2"))
  }

  @Test
  fun eventRingAndSubscriberLimit() {
    val base = broker.latestSeq()
    repeat(600) { broker.publish("book.added", "book-$it") }

    assertEquals(base + 600, broker.latestSeq())
    assertEquals(base + 600 - (PluginEventBroker.BUFFER_SIZE - 1), broker.oldestSeq())

    val pluginId = "test.subscribers"
    val first = broker.open(pluginId, null)
    val second = broker.open(pluginId, null)
    assertEquals(2, broker.subscriberCount(pluginId))
    assertThrows(PluginSubscriptionException::class.java) { broker.open(pluginId, null) }

    first.complete()
    second.complete()
  }

  private fun cleanup() {
    store.findAllInstances().forEach { store.deleteInstance(it.id) }
    apiTokens
      .findAll()
      .filter { it.name.startsWith(PluginTokenService.TOKEN_NAME_PREFIX) || it.name == MANUAL_TOKEN }
      .forEach { apiTokens.deleteById(it.id) }
    listOf("test.p2", "test.plain").forEach { pluginId -> store.listKv(pluginId, 0, 500).forEach { store.deleteKv(pluginId, it.key) } }
  }

  private fun adminUser(): LantaiUser =
    users.findAll().firstOrNull { it.isAdmin } ?: LantaiUser(
      email = "plugin-p2@example.invalid",
      password = "{noop}plugin-p2",
      roles = setOf(UserRoles.ADMIN),
      username = "plugin-p2-admin",
    ).also { users.insert(it) }

  private fun pluginToken(
    pluginId: String,
    scopes: Set<ApiToken.Scope>,
  ): String {
    val token =
      ApiToken(
        name = PluginTokenService.TOKEN_NAME_PREFIX + pluginId,
        ownerId = adminUser().id,
        tokenValue = "lt_p2_" + pluginId.replace('.', '_'),
        scopes = scopes,
      )
    apiTokens.insert(token)
    return token.tokenValue
  }

  private fun perform(
    builder: MockHttpServletRequestBuilder,
    token: String,
    body: String?,
  ): MvcResult {
    val request = builder.header(ApiTokenGenerator.HEADER, token).contentType(MediaType.APPLICATION_JSON)
    if (body != null) request.content(body)
    return mockMvc.perform(request).andReturn()
  }

  private fun json(result: MvcResult): JsonNode = mapper.readTree(result.response.contentAsString)

  companion object {
    private const val MANUAL_TOKEN = "manual-p2"
  }
}
