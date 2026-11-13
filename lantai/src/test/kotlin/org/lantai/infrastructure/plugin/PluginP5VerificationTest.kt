package org.lantai.infrastructure.plugin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.lantai.domain.model.ApiToken
import org.lantai.domain.model.LantaiUser
import org.lantai.domain.model.UserRoles
import org.lantai.domain.persistence.ApiTokenRepository
import org.lantai.domain.persistence.LantaiUserRepository
import org.lantai.domain.persistence.PluginStore
import org.lantai.infrastructure.security.apitoken.ApiTokenGenerator
import org.lantai.infrastructure.security.LantaiPrincipal
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders as Requests
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.net.InetSocketAddress

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("localdb")
class PluginP5VerificationTest(
  @Autowired private val mockMvc: MockMvc,
  @Autowired private val instances: PluginInstanceService,
  @Autowired private val store: PluginStore,
  @Autowired private val apiTokens: ApiTokenRepository,
  @Autowired private val users: LantaiUserRepository,
) {
  private val mapper = ObjectMapper()
  private val servers = mutableListOf<HttpServer>()

  @AfterEach
  fun tidy() {
    servers.forEach { it.stop(0) }
    servers.clear()
    PLUGIN_IDS.forEach { pluginId ->
      store.findByPluginIdOrNull(pluginId)?.let { store.deleteInstance(it.id) } ?: store.deleteGrantsByPluginId(pluginId)
      apiTokens.findAll().filter { it.name == PluginTokenService.TOKEN_NAME_PREFIX + pluginId }.forEach { apiTokens.deleteById(it.id) }
    }
  }

  @Test
  fun credentialsAreEncryptedAtRest() {
    val endpoint = serve(FULL_PLUGIN)
    val instanceId = register(endpoint).path("id").asText()
    val token = pluginToken(FULL_PLUGIN, setOf(ApiToken.Scope.CREDENTIAL_STORE))
    val write = mockMvc.perform(json(Requests.put("/api/lantai/v1/plugin/credentials/mailPassword"), token, """{"value":"s3cr3t-值"}""")).andReturn()
    assertEquals(200, write.response.status)
    val read = mockMvc.perform(Requests.get("/api/lantai/v1/plugin/credentials/mailPassword").header(ApiTokenGenerator.HEADER, token)).andReturn()
    assertEquals(200, read.response.status)
    assertEquals("s3cr3t-值", mapper.readTree(read.response.contentAsString).path("value").asText())
    val stored = store.findCredentialOrNull(FULL_PLUGIN, "mailPassword")
    assertTrue(stored != null && stored.value.startsWith("enc:"), "落库必须是密文，实际：${stored?.value}")
    val listed = mockMvc.perform(Requests.get("/api/lantai/v1/plugin/credentials").header(ApiTokenGenerator.HEADER, token)).andReturn()
    assertTrue(listed.response.contentAsString.indexOf("s3cr3t") < 0, "列表不得回显凭据值")
    assertEquals(1, mapper.readTree(listed.response.contentAsString).size())
    val deleted = mockMvc.perform(Requests.delete("/api/lantai/v1/plugin/credentials/mailPassword").header(ApiTokenGenerator.HEADER, token)).andReturn()
    assertEquals(204, deleted.response.status)
    val gone = mockMvc.perform(Requests.get("/api/lantai/v1/plugin/credentials/mailPassword").header(ApiTokenGenerator.HEADER, token)).andReturn()
    assertEquals(404, gone.response.status)
  }

  @Test
  fun credentialNameAndSizeAreValidated() {
    val endpoint = serve(FULL_PLUGIN)
    register(endpoint)
    val token = pluginToken(FULL_PLUGIN, setOf(ApiToken.Scope.CREDENTIAL_STORE))
    val badName = mockMvc.perform(json(Requests.put("/api/lantai/v1/plugin/credentials/-bad"), token, """{"value":"x"}""")).andReturn()
    assertEquals(400, badName.response.status)
    val huge = "x".repeat(5000)
    val tooLarge = mockMvc.perform(json(Requests.put("/api/lantai/v1/plugin/credentials/big"), token, """{"value":"$huge"}""")).andReturn()
    assertEquals(413, tooLarge.response.status)
  }

  @Test
  fun credentialsRequireScope() {
    val endpoint = serve(FULL_PLUGIN)
    register(endpoint)
    val token = pluginToken(FULL_PLUGIN, setOf(ApiToken.Scope.STORAGE_BLOB))
    val denied = mockMvc.perform(json(Requests.put("/api/lantai/v1/plugin/credentials/k"), token, """{"value":"x"}""")).andReturn()
    assertEquals(403, denied.response.status)
  }

  @Test
  fun adminListsAndDeletesCredentials() {
    val endpoint = serve(FULL_PLUGIN)
    val instanceId = register(endpoint).path("id").asText()
    instances.updateGrants(instanceId, listOf("credential.store"), null, "p5-test")
    val token = pluginToken(FULL_PLUGIN, setOf(ApiToken.Scope.CREDENTIAL_STORE))
    mockMvc.perform(json(Requests.put("/api/lantai/v1/plugin/credentials/apiKey"), token, """{"value":"abc"}""")).andReturn()
    val listed = mockMvc.perform(Requests.get("/api/lantai/v1/plugins/$instanceId/credentials").with(user(caller()))).andReturn()
    assertEquals(200, listed.response.status)
    val rows = mapper.readTree(listed.response.contentAsString)
    assertEquals(1, rows.size())
    assertEquals("apiKey", rows[0].path("name").asText())
    assertTrue(listed.response.contentAsString.indexOf("abc") < 0, "管理侧也不得回显值")
    val deleted = mockMvc.perform(Requests.delete("/api/lantai/v1/plugins/$instanceId/credentials/apiKey").with(user(caller()))).andReturn()
    assertEquals(204, deleted.response.status)
    assertTrue(store.findCredentialOrNull(FULL_PLUGIN, "apiKey") == null, "删除后不应留痕")
  }

  @Test
  fun pollsAsynchronousJobUntilDone() {
    val audio = ByteArray(128) { it.toByte() }
    var polls = 0
    val endpoint = serve(FULL_PLUGIN) { path, exchange ->
      when {
        path == "/v1/tts" -> respond(exchange, 202, "application/json", """{"jobId":"job-1"}""".toByteArray())
        path == "/v1/jobs/job-1" -> {
          polls += 1
          if (polls == 1) {
            respond(exchange, 200, "application/json", """{"state":"running","progress":0.4}""".toByteArray())
          } else {
            respond(exchange, 200, "audio/wav", audio)
          }
        }
      }
    }
    val instanceId = register(endpoint).path("id").asText()
    grantTts(instanceId)
    val result = speak()
    assertEquals(200, result.response.status)
    assertTrue(polls >= 2, "应至少轮询两次，实际 $polls")
    assertTrue(result.response.contentAsByteArray.contentEquals(audio), "最终应拿到作业产物")
  }

  @Test
  fun surfacesAsynchronousJobFailure() {
    val endpoint = serve(FULL_PLUGIN) { path, exchange ->
      when (path) {
        "/v1/tts" -> respond(exchange, 202, "application/json", """{"jobId":"job-2"}""".toByteArray())
        "/v1/jobs/job-2" -> respond(exchange, 200, "application/json", """{"state":"failed","message":"engine boom"}""".toByteArray())
      }
    }
    val instanceId = register(endpoint).path("id").asText()
    grantTts(instanceId)
    val result = speak()
    assertEquals(503, result.response.status)
    val message = result.resolvedException?.message ?: ""
    assertTrue(message.contains("engine boom"), "应带回作业失败原因，实际：$message")
  }

  @Test
  fun settingsRoundTrip() {
    val endpoint = serve(FULL_PLUGIN)
    val instanceId = register(endpoint).path("id").asText()
    val saved = mockMvc.perform(json(Requests.patch("/api/lantai/v1/plugins/$instanceId/settings"), caller(), """{"voice":"zh-CN","rate":1.2}""")).andReturn()
    assertEquals(200, saved.response.status)
    val dto = mapper.readTree(saved.response.contentAsString)
    assertEquals("zh-CN", mapper.readTree(dto.path("settings").asText()).path("voice").asText())
    assertEquals("string", dto.path("config").path("properties").path("voice").path("type").asText())
    val reloaded = mockMvc.perform(Requests.get("/api/lantai/v1/plugins").with(user(caller()))).andReturn()
    val row = mapper.readTree(reloaded.response.contentAsString).first { it.path("id").asText() == instanceId }
    assertEquals("zh-CN", mapper.readTree(row.path("settings").asText()).path("voice").asText())
    val arrayBody = mockMvc.perform(json(Requests.patch("/api/lantai/v1/plugins/$instanceId/settings"), caller(), """[1,2]""")).andReturn()
    assertEquals(400, arrayBody.response.status)
  }

  @Test
  fun recordsRecentCalls() {
    val endpoint = serve(FULL_PLUGIN) { path, exchange ->
      if (path == "/v1/tts") respond(exchange, 200, "audio/wav", ByteArray(16))
    }
    val instanceId = register(endpoint).path("id").asText()
    grantTts(instanceId)
    assertEquals(200, speak().response.status)
    val calls = mockMvc.perform(Requests.get("/api/lantai/v1/plugins/$instanceId/calls?limit=5").with(user(caller()))).andReturn()
    assertEquals(200, calls.response.status)
    val rows = mapper.readTree(calls.response.contentAsString)
    assertTrue(rows.size() >= 1, "应至少记录一次调用")
    val hit = rows.firstOrNull { it.path("capability").asText() == "tts.speak" && it.path("success").asBoolean() }
    assertTrue(hit != null, "应存在一条成功的 tts.speak 记录")
    assertTrue(hit!!.path("durationMs").asLong() >= 0)
  }

  private fun speak(): MvcResult =
    mockMvc
      .perform(
        Requests
          .post("/api/lantai/v1/capabilities/tts.speak")
          .with(user(caller()))
          .contentType(MediaType.APPLICATION_JSON)
          .content("""{"text":"作业化朗读"}"""),
      ).andReturn()

  private fun grantTts(instanceId: String) {
    clearGrants("tts.speak")
    instances.updateGrants(instanceId, listOf("tts.speak"), "tts.speak", "p5-test")
  }

  private fun clearGrants(capability: String) {
    store.findAllInstances().forEach { instance ->
      val kept = store.findGrantsByPluginId(instance.pluginId).filter { it.capability != capability }
      store.replaceGrants(instance.pluginId, kept)
    }
  }

  private fun serve(
    pluginId: String,
    handlers: (String, HttpExchange) -> Unit = { _, _ -> },
  ): String {
    val key = "p5-key-$pluginId"
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange ->
      try {
        if (exchange.requestHeaders.getFirst("X-Lantai-Host-Key") != key) {
          exchange.sendResponseHeaders(401, -1)
          return@createContext
        }
        val path = exchange.requestURI.path
        when (path) {
          "/v1/manifest" -> respond(exchange, 200, "application/json", manifestJson(pluginId).toByteArray())
          "/v1/health" -> respond(exchange, 200, "application/json", """{"status":"ok"}""".toByteArray())
          else -> {
            handlers(path, exchange)
            if (exchange.responseCode == -1) exchange.sendResponseHeaders(404, -1)
          }
        }
      } finally {
        exchange.close()
      }
    }
    server.start()
    servers += server
    return "http://127.0.0.1:${server.address.port}"
  }

  private fun manifestJson(pluginId: String): String =
    """
    {"id":"$pluginId","name":"P5 Fake $pluginId","version":"1.0.0","protocol":2,"minHost":"0.6.0",
     "capabilities":{"tts.speak":"/v1/tts","credential.store":"/v1/credentials"},
     "config":{"type":"object","properties":{"voice":{"type":"string"},"rate":{"type":"number"}}}}
    """.trimIndent()

  private fun respond(
    exchange: HttpExchange,
    status: Int,
    contentType: String,
    payload: ByteArray,
  ) {
    exchange.responseHeaders.add("Content-Type", contentType)
    exchange.sendResponseHeaders(status, payload.size.toLong())
    exchange.responseBody.use { it.write(payload) }
  }

  private fun register(endpoint: String): JsonNode {
    val result =
      mockMvc
        .perform(
          Requests
            .post("/api/lantai/v1/plugins")
            .with(user(caller()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(mapOf("endpoint" to endpoint, "hostKey" to "p5-key-$FULL_PLUGIN"))),
        ).andExpect(status().isCreated)
        .andReturn()
    return mapper.readTree(result.response.contentAsString)
  }

  private fun pluginToken(
    pluginId: String,
    scopes: Set<ApiToken.Scope>,
  ): String {
    val name = PluginTokenService.TOKEN_NAME_PREFIX + pluginId
    apiTokens.findAll().filter { it.name == name }.forEach { apiTokens.deleteById(it.id) }
    val token =
      ApiToken(
        name = name,
        ownerId = admin().id,
        tokenValue = "lt_p5_" + pluginId.replace('.', '_'),
        scopes = scopes,
      )
    apiTokens.insert(token)
    return token.tokenValue
  }

  private fun json(
    builder: MockHttpServletRequestBuilder,
    caller: Any,
    body: String,
  ): MockHttpServletRequestBuilder {
    val request =
      when (caller) {
        is String -> builder.header(ApiTokenGenerator.HEADER, caller)
        is LantaiPrincipal -> builder.with(user(caller))
        else -> builder
      }
    return request.contentType(MediaType.APPLICATION_JSON).content(body)
  }

  private fun caller(): LantaiPrincipal = LantaiPrincipal(admin())

  private fun admin(): LantaiUser =
    users.findAll().firstOrNull { it.isAdmin }
      ?: LantaiUser(
        email = "plugin-p5@lantai.local",
        password = "plugin-p5",
        roles = setOf(UserRoles.ADMIN),
        username = "plugin-p5-admin",
      ).also { users.insert(it) }

  private companion object {
    private const val FULL_PLUGIN = "com.example.p5.full"
    private val PLUGIN_IDS = listOf(FULL_PLUGIN)
  }
}
