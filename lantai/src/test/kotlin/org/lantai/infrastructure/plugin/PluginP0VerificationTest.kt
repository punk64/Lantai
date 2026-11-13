package org.lantai.infrastructure.plugin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.lantai.domain.persistence.PluginStore
import org.lantai.jooq.main.Tables
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders as Requests
import java.net.InetSocketAddress

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("localdb")
class PluginP0VerificationTest(
  @Autowired private val mockMvc: MockMvc,
  @Autowired private val store: PluginStore,
  @Autowired private val instances: PluginInstanceService,
  @Autowired @Qualifier("dslContextRO") private val dslRO: DSLContext,
) {
  private val mapper = ObjectMapper()

  @Test
  fun p0Lifecycle() {
    store.findAllInstances().forEach { store.deleteInstance(it.id) }

    val fake = FakePlugin().start()
    try {
      val endpoint = "http://127.0.0.1:${fake.port}"

      val (registeredStatus, created) =
        call(postJson("/api/lantai/v1/plugins", """{"endpoint":"$endpoint","hostKey":"$HOST_KEY"}"""))
      assertEquals(201, registeredStatus, "注册应返回 201")
      assertEquals("READY", created.text("state"), "握手应 READY")
      assertEquals(2, created.path("apiVersion").asInt(), "protocol=2 应落库为 apiVersion=2")
      assertEquals(listOf("tts.speak"), created.path("capabilities").map { it.asText() }, "对象形态 capabilities 应被解析")
      assertEquals(HOST_KEY, created.text("hostKey"), "注册响应应一次性返回明文 hostKey")
      val instanceId = created.text("id")
      assertTrue(instanceId.isNotEmpty(), "应返回实例 id")

      val storedKey =
        dslRO
          .select(Tables.PLUGIN_INSTANCE.HOST_KEY)
          .from(Tables.PLUGIN_INSTANCE)
          .where(Tables.PLUGIN_INSTANCE.ID.eq(instanceId))
          .fetchOne(Tables.PLUGIN_INSTANCE.HOST_KEY)
      assertNotNull(storedKey, "hostKey 应已落库")
      assertTrue(storedKey!!.startsWith("enc:"), "hostKey 必须加密存储，实际以 ${storedKey.take(4)} 开头")

      val (listStatus, listed) = call(mockMvc.perform(auth(Requests.get("/api/lantai/v1/plugins"))))
      assertEquals(200, listStatus, "列表应 200")
      val masked = listed.first().text("hostKey")
      assertTrue(masked.contains("…") && masked != HOST_KEY, "列表应返回掩码，实际 $masked")

      val (refreshStatus, refreshed) = call(postJson("/api/lantai/v1/plugins/$instanceId/refresh"))
      assertEquals(200, refreshStatus, "刷新应 200")
      assertEquals("READY", refreshed.text("state"), "刷新后仍 READY")
      assertEquals(2, refreshed.path("apiVersion").asInt(), "刷新后协议版本保持 2")

      val grants = instances.updateGrants(instanceId, listOf("tts.speak"), "tts.speak", null)
      assertEquals(1, grants.size, "应写入 1 条授权")
      assertTrue(grants.single().isDefault, "应标记为默认实现")
      assertEquals("com.example.fake", store.findDefaultPluginId("tts.speak"), "默认实现应能按能力反查")

      fake.protocol = 3
      val (_, badProtocol) = call(postJson("/api/lantai/v1/plugins/$instanceId/refresh"))
      assertEquals("INCOMPATIBLE", badProtocol.text("state"), "protocol=3 应 INCOMPATIBLE")
      assertTrue(badProtocol.text("statusMessage").contains("协议版本"), "应给出协议原因")

      fake.protocol = 2
      fake.minHost = "9.9.9"
      val (_, badMinHost) = call(postJson("/api/lantai/v1/plugins/$instanceId/refresh"))
      assertEquals("INCOMPATIBLE", badMinHost.text("state"), "minHost 过高应 INCOMPATIBLE")
      assertTrue(badMinHost.text("statusMessage").contains("9.9.9"), "应给出 minHost 原因")

      fake.minHost = "0.6.0"
      fake.hostKey = "server-side-key"
      val (_, badKey) =
        call(postJson("/api/lantai/v1/plugins", """{"endpoint":"$endpoint","hostKey":"wrong-key"}"""))
      assertEquals("AUTH_FAILED", badKey.text("state"), "hostKey 不匹配应 AUTH_FAILED")

      fake.hostKey = HOST_KEY
      fake.stop()
      val (_, down) = call(postJson("/api/lantai/v1/plugins/$instanceId/refresh"))
      assertEquals("UNAVAILABLE", down.text("state"), "插件停止应 UNAVAILABLE")

      val (deletedStatus, _) = call(mockMvc.perform(auth(Requests.delete("/api/lantai/v1/plugins/$instanceId"))))
      assertEquals(204, deletedStatus, "删除应 204")
      assertTrue(store.findGrantsByPluginId("com.example.fake").isEmpty(), "删除应连带清空授权")
      assertNull(store.findInstanceByIdOrNull(instanceId), "实例应已删除")
    } finally {
      fake.stop()
      store.findAllInstances().forEach { store.deleteInstance(it.id) }
    }
  }

  private fun postJson(
    url: String,
    body: String? = null,
  ): ResultActions {
    val builder = auth(Requests.post(url))
    if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(body)
    return mockMvc.perform(builder)
  }

  private fun auth(builder: MockHttpServletRequestBuilder): MockHttpServletRequestBuilder =
    builder.with(user("admin").roles("ADMIN"))

  private fun call(actions: ResultActions): Pair<Int, JsonNode> {
    val result = actions.andReturn()
    return result.response.status to mapper.readTree(result.response.contentAsString)
  }

  private fun JsonNode.text(field: String): String = path(field).asText("")

  private inner class FakePlugin {
    @Volatile var protocol: Int = 2
    @Volatile var minHost: String = "0.6.0"
    @Volatile var hostKey: String = HOST_KEY
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val port: Int get() = server.address.port

    fun start(): FakePlugin {
      server.createContext("/v1/manifest") { exchange ->
        respond(
          exchange,
          """{"id":"com.example.fake","name":"Fake Plugin","version":"1.0.0","protocol":$protocol,"minHost":"$minHost","capabilities":{"tts.speak":"/v1/tts"}}""",
        )
      }
      server.createContext("/v1/health") { exchange ->
        respond(exchange, """{"status":"ok","uptimeSeconds":1}""")
      }
      server.start()
      return this
    }

    fun stop() = server.stop(0)

    private fun respond(
      exchange: HttpExchange,
      body: String,
    ) {
      if (exchange.requestHeaders.getFirst("X-Lantai-Host-Key") != hostKey) {
        val error = """{"code":"unauthorized","message":"bad host key"}""".toByteArray()
        exchange.sendResponseHeaders(401, error.size.toLong())
        exchange.responseBody.use { it.write(error) }
        return
      }
      val bytes = body.toByteArray()
      exchange.responseHeaders.add("Content-Type", "application/json")
      exchange.sendResponseHeaders(200, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
    }
  }

  private companion object {
    private const val HOST_KEY = "test-host-key-123"
  }
}
