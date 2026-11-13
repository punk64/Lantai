package org.lantai.infrastructure.plugin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.lantai.domain.model.LantaiUser
import org.lantai.domain.model.UserRoles
import org.lantai.domain.persistence.ApiTokenRepository
import org.lantai.domain.persistence.LantaiUserRepository
import org.lantai.domain.persistence.PluginStore
import org.lantai.infrastructure.security.LantaiPrincipal
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders as Requests
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.net.InetSocketAddress

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("localdb")
class PluginP4VerificationTest(
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
    }
    apiTokens.findAll().filter { it.name.startsWith(PluginTokenService.TOKEN_NAME_PREFIX) }.forEach { apiTokens.deleteById(it.id) }
  }

  @Test
  fun speaksThroughDeclaredPath() {
    val audio = ByteArray(512) { (it % 251).toByte() }
    val endpoint = serve(TTS_PLUGIN, """{"tts.speak":"/v1/tts"}""") { path, exchange ->
      if (path == "/v1/tts") {
        val payload = mapper.readTree(readText(exchange))
        assertEquals("早安，兰台", payload.path("text").asText())
        assertEquals(2.0, payload.path("rate").asDouble(), "rate 应被夹到上限")
        respond(exchange, 200, "audio/wav", audio)
      }
    }
    val created = register(endpoint, keyOf(TTS_PLUGIN))
    grant(created.path("id").asText(), "tts.speak")
    val result =
      mockMvc
        .perform(
          Requests
            .post("/api/lantai/v1/capabilities/tts.speak")
            .with(user(caller()))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"text":"早安，兰台","rate":9.0}"""),
        ).andReturn()
    if (result.response.status != 200) {
      throw AssertionError("status=${result.response.status} msg=${result.resolvedException?.message} body=${result.response.contentAsString}")
    }
    assertArrayEquals(audio, result.response.contentAsByteArray, "音频字节应原样透传")
    assertTrue(result.response.contentType?.startsWith("audio/wav") == true, "应透传插件的音频类型")
  }

  @Test
  fun fallsBackToConventionalPath() {
    val audio = ByteArray(64) { it.toByte() }
    val endpoint = serve(FALLBACK_PLUGIN, """["tts.speak"]""") { path, exchange ->
      if (path == "/v1/capabilities/tts.speak") {
        readText(exchange)
        respond(exchange, 200, "audio/mpeg", audio)
      }
    }
    val created = register(endpoint, keyOf(FALLBACK_PLUGIN))
    grant(created.path("id").asText(), "tts.speak")
    val result =
      mockMvc
        .perform(
          Requests
            .post("/api/lantai/v1/capabilities/tts.speak")
            .with(user(caller()))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"text":"回落路径"}"""),
        ).andExpect(status().isOk)
        .andReturn()
    assertArrayEquals(audio, result.response.contentAsByteArray, "数组形态应回落到约定路径")
  }

  @Test
  fun reportsMissingProvider() {
    clearGrants("tts.speak")
    mockMvc
      .perform(
        Requests
          .post("/api/lantai/v1/capabilities/tts.speak")
          .with(user(caller()))
          .contentType(MediaType.APPLICATION_JSON)
          .content("""{"text":"没人负责"}"""),
      ).andExpect(status().isServiceUnavailable)
    val listed = listCapabilities()
    assertEquals(false, listed.first { it.path("capability").asText() == "tts.speak" }.path("available").asBoolean())
  }

  @Test
  fun listsDefaultProvider() {
    clearGrants("tts.speak")
    val endpoint = serve(TTS_PLUGIN, """{"tts.speak":"/v1/tts"}""") { _, _ -> }
    val created = register(endpoint, keyOf(TTS_PLUGIN))
    grant(created.path("id").asText(), "tts.speak")
    val entry = listCapabilities().first { it.path("capability").asText() == "tts.speak" }
    assertEquals(true, entry.path("available").asBoolean())
    assertEquals(TTS_PLUGIN, entry.path("pluginId").asText())
    assertEquals(1, entry.path("candidates").asInt())
  }

  @Test
  fun sendsNotification() {
    val endpoint = serve(NOTIFY_PLUGIN, """{"notify.send":"/v1/notify"}""") { path, exchange ->
      if (path == "/v1/notify") {
        val payload = mapper.readTree(readText(exchange))
        assertEquals("到期提醒", payload.path("title").asText())
        assertEquals("warning", payload.path("level").asText())
        respond(exchange, 200, "application/json", """{"ok":true}""".toByteArray())
      }
    }
    val created = register(endpoint, keyOf(NOTIFY_PLUGIN))
    grant(created.path("id").asText(), "notify.send")
    val result =
      mockMvc
        .perform(
          Requests
            .post("/api/lantai/v1/capabilities/notify.send")
            .with(user(caller()))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"title":"到期提醒","body":"三本书今天到期","level":"warning"}"""),
        ).andExpect(status().isOk)
        .andReturn()
    val body = mapper.readTree(result.response.contentAsString)
    assertEquals(true, body.path("delivered").asBoolean())
    assertEquals(NOTIFY_PLUGIN, body.path("pluginId").asText())
  }

  @Test
  fun surfacesProviderFailure() {
    val endpoint = serve(TTS_PLUGIN, """{"tts.speak":"/v1/tts"}""") { path, exchange ->
      if (path == "/v1/tts") {
        readText(exchange)
        respond(exchange, 500, "text/plain", "engine down".toByteArray())
      }
    }
    val created = register(endpoint, keyOf(TTS_PLUGIN))
    grant(created.path("id").asText(), "tts.speak")
    val message = failureMessage("""{"text":"会失败"}""")
    assertTrue(message.contains("HTTP 500"), "应暴露上游状态码，实际：$message")
    assertTrue(message.contains("engine down"), "应带回上游错误摘要，实际：$message")
  }

  @Test
  fun rejectsOversizedResponse() {
    val endpoint = serve(TTS_PLUGIN, """{"tts.speak":"/v1/tts"}""") { path, exchange ->
      if (path == "/v1/tts") {
        readText(exchange)
        runCatching { respond(exchange, 200, "audio/wav", ByteArray(9 * 1024 * 1024)) }
      }
    }
    val created = register(endpoint, keyOf(TTS_PLUGIN))
    grant(created.path("id").asText(), "tts.speak")
    val message = failureMessage("""{"text":"太大"}""")
    assertTrue(message.contains("上限"), "应拒绝超限响应，实际：$message")
  }

  @Test
  fun requiresAuthentication() {
    mockMvc.perform(Requests.get("/api/lantai/v1/capabilities")).andExpect(status().isUnauthorized())
    mockMvc
      .perform(
        Requests
          .post("/api/lantai/v1/capabilities/tts.speak")
          .contentType(MediaType.APPLICATION_JSON)
          .content("""{"text":"匿名"}"""),
      ).andExpect(status().isUnauthorized)
  }

  private fun failureMessage(ttsBody: String): String {
    val result =
      mockMvc
        .perform(
          Requests
            .post("/api/lantai/v1/capabilities/tts.speak")
            .with(user(caller()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(ttsBody),
        ).andExpect(status().isServiceUnavailable)
        .andReturn()
    return result.resolvedException?.message ?: result.response.contentAsString
  }

  private fun listCapabilities(): List<JsonNode> {
    val result =
      mockMvc
        .perform(Requests.get("/api/lantai/v1/capabilities").with(user(caller())))
        .andExpect(status().isOk)
        .andReturn()
    return mapper.readTree(result.response.contentAsString).toList()
  }

  private fun keyOf(pluginId: String): String = "p4-key-$pluginId"

  private fun serve(
    pluginId: String,
    capabilitiesJson: String,
    handlers: (String, HttpExchange) -> Unit,
  ): String {
    val key = keyOf(pluginId)
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange ->
      try {
        if (exchange.requestHeaders.getFirst("X-Lantai-Host-Key") != key) {
          exchange.sendResponseHeaders(401, -1)
          return@createContext
        }
        val path = exchange.requestURI.path
        when (path) {
          "/v1/manifest" ->
            respond(
              exchange,
              200,
              "application/json",
              """{"id":"$pluginId","name":"P4 Fake $pluginId","version":"1.0.0","protocol":2,"minHost":"0.6.0","capabilities":$capabilitiesJson}""".toByteArray(),
            )
          "/v1/health" -> respond(exchange, 200, "application/json", """{"status":"ok"}""".toByteArray())
          else -> {
            handlers(path, exchange)
            if (exchange.responseCode == -1) {
              exchange.sendResponseHeaders(404, -1)
            }
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

  private fun readText(exchange: HttpExchange): String = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)

  private fun register(
    endpoint: String,
    hostKey: String,
  ): JsonNode {
    val result =
      mockMvc
        .perform(
          Requests
            .post("/api/lantai/v1/plugins")
            .with(user(caller()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(mapOf("endpoint" to endpoint, "hostKey" to hostKey))),
        ).andExpect(status().isCreated)
        .andReturn()
    return mapper.readTree(result.response.contentAsString)
  }

  private fun grant(
    instanceId: String,
    capability: String,
  ) {
    clearGrants(capability)
    instances.updateGrants(instanceId, listOf(capability), capability, "p4-test")
  }

  private fun clearGrants(capability: String) {
    store.findAllInstances().forEach { instance ->
      val kept = store.findGrantsByPluginId(instance.pluginId).filter { it.capability != capability }
      store.replaceGrants(instance.pluginId, kept)
    }
  }

  private fun caller(): LantaiPrincipal = LantaiPrincipal(admin())

  private fun admin(): LantaiUser =
    users.findAll().firstOrNull { it.isAdmin }
      ?: LantaiUser(
        email = "plugin-p4@lantai.local",
        password = "plugin-p4",
        roles = setOf(UserRoles.ADMIN),
        username = "plugin-p4-admin",
      ).also { users.insert(it) }

  private companion object {
    private const val TTS_PLUGIN = "com.example.p4.tts"
    private const val NOTIFY_PLUGIN = "com.example.p4.notify"
    private const val FALLBACK_PLUGIN = "com.example.p4.fallback"
    private val PLUGIN_IDS = listOf(TTS_PLUGIN, NOTIFY_PLUGIN, FALLBACK_PLUGIN)
  }
}
