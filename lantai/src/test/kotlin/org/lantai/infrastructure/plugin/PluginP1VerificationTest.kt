package org.lantai.infrastructure.plugin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.lantai.domain.model.ApiToken
import org.lantai.domain.model.LantaiUser
import org.lantai.domain.model.PluginState
import org.lantai.domain.model.UserRoles
import org.lantai.domain.persistence.ApiTokenRepository
import org.lantai.domain.persistence.LantaiUserRepository
import org.lantai.domain.persistence.PluginStore
import org.lantai.infrastructure.security.LantaiPrincipal
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.User
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders as Requests
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("localdb")
class PluginP1VerificationTest(
  @Autowired private val mockMvc: MockMvc,
  @Autowired private val instances: PluginInstanceService,
  @Autowired private val paths: PluginPaths,
  @Autowired private val store: PluginStore,
  @Autowired private val monitor: PluginHealthMonitor,
  @Autowired private val processManager: PluginProcessManager,
  @Autowired private val apiTokens: ApiTokenRepository,
  @Autowired private val users: LantaiUserRepository,
) {
  private val mapper = ObjectMapper()

  @Test
  fun managedLifecycle() {
    instances.list().forEach { instances.delete(it.id) }
    apiTokens.findAll().filter { it.name.startsWith(PluginTokenService.TOKEN_NAME_PREFIX) }.forEach { apiTokens.deleteById(it.id) }
    runCatching {
      val stale = paths.packageDir("com.example.managed", "1.0.0")
      deleteRecursively(stale)
      stale.parent?.let { deleteRecursively(it) }
    }
    val existingAdmin = users.findAll().firstOrNull { it.isAdmin }
    val owner =
      existingAdmin ?: LantaiUser(
        email = "plugin-e2e@lantai.local",
        password = "plugin-e2e",
        roles = setOf(UserRoles.ADMIN),
        username = "plugin-e2e-admin",
      ).also { users.insert(it) }
    val caller = LantaiPrincipal(owner)
    val work = Files.createTempDirectory("lantai-plugin-p1")
    try {
      val pkg = buildPackage(work)
      val startedAt = System.currentTimeMillis()
      val (installStatus, created) =
        call(postJson("/api/lantai/v1/plugins/install", """{"zipPath":${mapper.writeValueAsString(pkg.toString())}}"""), caller)
      val elapsed = System.currentTimeMillis() - startedAt
      assertEquals(201, installStatus, "安装应返回 201")
      assertEquals("READY", created.text("state"), "managed 插件应握手成功")
      assertEquals("MANAGED", created.text("mode"), "安装模式应为 managed")
      assertEquals("command", created.text("runtimeKind"), "runtimeKind 应来自清单")
      assertTrue(created.path("pid").asInt(0) > 0, "READY 实例应记录 pid")
      assertTrue(elapsed < 10_000, "冷启动应在 10 秒内完成，实际 ${elapsed}ms")

      val instanceId = created.text("id")
      val endpoint = created.text("endpoint")
      val port = URI.create(endpoint).port
      assertTrue(port in PluginProcessManager.PORT_MIN..PluginProcessManager.PORT_MAX, "端口应落在 26000-26999，实际 $port")

      val home = paths.packageDir("com.example.managed", "1.0.0")
      assertTrue(home.exists(), "插件应解压到 ${paths.root()} 下")

      val tokenName = "${PluginTokenService.TOKEN_NAME_PREFIX}com.example.managed"
      val issued = apiTokens.findAll().singleOrNull { it.name == tokenName }
      assertNotNull(issued, "安装应签发插件令牌")
      assertTrue(issued!!.tokenValue.startsWith("lt_"), "令牌应为 lt_ 前缀")
      assertEquals(setOf(ApiToken.Scope.TTS_SPEAK), issued.scopes, "安装应按清单自动授权并同步令牌 scope")
      assertEquals(owner.id, issued.ownerId, "令牌应归属发起安装的管理员")

      val env = mapper.readTree(home.parent.resolve("data/env.json").toFile())
      assertEquals(issued.tokenValue, env.path("LANTAI_PLUGIN_TOKEN").asText(""), "插件进程应拿到同一把令牌")
      assertEquals("com.example.managed", env.path("LANTAI_PLUGIN_ID").asText(""), "插件进程应拿到插件 id")

      val granted = instances.updateGrants(instanceId, listOf("tts.speak"), "tts.speak", "admin")
      assertEquals(1, granted.size, "应写入 1 条授权")
      assertTrue(granted.single().isDefault, "应标记为默认实现")
      assertEquals(setOf(ApiToken.Scope.TTS_SPEAK), apiTokens.findByIdOrNull(issued.id)?.scopes, "令牌 scope 应随授权同步")

      processManager.stop(instanceId)
      repeat(PluginHealthMonitor.FAILURE_THRESHOLD) { monitor.probeAll() }
      val degraded = instances.list().single { it.id == instanceId }
      assertEquals(PluginState.UNAVAILABLE, degraded.state, "连续失败应降级为 UNAVAILABLE")
      assertNull(degraded.pid, "降级后应清空 pid")

      val revived = instances.refresh(instanceId)
      assertEquals(PluginState.READY, revived.state, "刷新应重新拉起")

      store.updateInstance(revived.copy(lastUsedDate = LocalDateTime.now().minusMinutes(PluginHealthMonitor.IDLE_MINUTES + 1)))
      monitor.reclaimIdle()
      val idled = instances.list().single { it.id == instanceId }
      assertEquals(PluginState.IDLE, idled.state, "空闲超时应回收为 IDLE")
      assertNull(idled.pid, "空闲回收后应清空 pid")
      assertFalse(processManager.isRunning(instanceId), "空闲回收后进程应退出")
      assertEquals(PluginState.READY, instances.refresh(instanceId).state, "空闲后刷新应冷启动回 READY")

      val (conflictStatus, _) =
        call(postJson("/api/lantai/v1/plugins/install", """{"zipPath":${mapper.writeValueAsString(pkg.toString())}}"""))
      assertEquals(409, conflictStatus, "重复安装应返回 409")

      val (disabledStatus, disabled) = call(json(Requests.patch("/api/lantai/v1/plugins/$instanceId"), """{"enabled":false}"""))
      assertEquals(200, disabledStatus, "停用应返回 200")
      assertEquals("DISABLED", disabled.text("state"), "停用后状态应为 DISABLED")
      assertTrue(disabled.path("pid").isNull || disabled.path("pid").isMissingNode, "停用后应清空 pid")

      val (enabledStatus, enabled) = call(json(Requests.patch("/api/lantai/v1/plugins/$instanceId"), """{"enabled":true}"""))
      assertEquals(200, enabledStatus, "启用应返回 200")
      assertEquals("READY", enabled.text("state"), "启用后应重新 READY")
      assertEquals(port, URI.create(enabled.text("endpoint")).port, "重启应复用同一端口")

      val (deleteStatus, _) = call(Requests.delete("/api/lantai/v1/plugins/$instanceId"))
      assertEquals(204, deleteStatus, "删除应返回 204")
      assertFalse(home.exists(), "删除应清理插件目录")
      assertTrue(instances.list().none { it.pluginId == "com.example.managed" }, "删除后不应残留实例")
      assertTrue(apiTokens.findAll().none { it.name == tokenName }, "删除应吊销插件令牌")
    } finally {
      instances.list().forEach { instances.delete(it.id) }
      if (existingAdmin == null) runCatching { users.delete(owner.id) }
      deleteRecursively(work)
    }
  }

  private fun buildPackage(dir: Path): Path {
    val manifest =
      """
      {
        "id": "com.example.managed",
        "name": "Managed Fake",
        "version": "1.0.0",
        "protocol": 2,
        "minHost": "0.6.0",
        "runtime": "command",
        "entry": "bin/run.bat",
        "memoryMb": 128,
        "capabilities": ["tts.speak"]
      }
      """.trimIndent()
    val script =
      """
      import json, os
      from http.server import BaseHTTPRequestHandler, HTTPServer

      KEY = os.environ["LANTAI_PLUGIN_HOST_KEY"]
      PORT = int(os.environ["LANTAI_PLUGIN_PORT"])
      DATA = os.environ.get("LANTAI_PLUGIN_DATA", ".")
      os.makedirs(DATA, exist_ok=True)
      with open(os.path.join(DATA, "env.json"), "w") as handle:
          json.dump({k: v for k, v in os.environ.items() if k.startswith("LANTAI_PLUGIN_")}, handle)

      class H(BaseHTTPRequestHandler):
          def do_GET(self):
              if self.headers.get("X-Lantai-Host-Key") != KEY:
                  self.send_response(401)
                  self.end_headers()
                  return
              if self.path == "/v1/manifest":
                  body = {"id": "com.example.managed", "name": "Managed Fake", "version": "1.0.0",
                          "protocol": 2, "minHost": "0.6.0", "runtime": "command",
                          "capabilities": ["tts.speak"]}
              elif self.path == "/v1/health":
                  body = {"status": "ok"}
              else:
                  self.send_response(404)
                  self.end_headers()
                  return
              data = json.dumps(body).encode()
              self.send_response(200)
              self.send_header("Content-Type", "application/json")
              self.send_header("Content-Length", str(len(data)))
              self.end_headers()
              self.wfile.write(data)

          def log_message(self, *args):
              pass

      HTTPServer(("127.0.0.1", PORT), H).serve_forever()
      """.trimIndent()
    val launcher = "@echo off\r\npython \"%~dp0plugin.py\"\r\n"
    val zip = dir.resolve("managed-plugin.zip")
    ZipOutputStream(Files.newOutputStream(zip)).use { out ->
      listOf(
        "plugin.json" to manifest,
        "bin/plugin.py" to script,
        "bin/run.bat" to launcher,
      ).forEach { (name, content) ->
        out.putNextEntry(ZipEntry(name))
        out.write(content.toByteArray())
        out.closeEntry()
      }
    }
    return zip
  }

  private fun json(
    builder: MockHttpServletRequestBuilder,
    body: String,
  ): MockHttpServletRequestBuilder = builder.contentType(MediaType.APPLICATION_JSON).content(body)

  private fun postJson(
    url: String,
    body: String,
  ): MockHttpServletRequestBuilder = json(Requests.post(url), body)

  private fun call(
    builder: MockHttpServletRequestBuilder,
    caller: UserDetails = User("admin", "", listOf(SimpleGrantedAuthority("ROLE_ADMIN"))),
  ): Pair<Int, JsonNode> {
    val action: ResultActions = mockMvc.perform(builder.with(user(caller)))
    val response = action.andReturn().response
    val content = response.contentAsString
    val node = if (content.isBlank()) mapper.createObjectNode() else mapper.readTree(content)
    return response.status to node
  }

  private fun JsonNode.text(field: String): String = path(field).asText("")

  private fun deleteRecursively(path: Path) {
    if (!path.exists()) return
    runCatching {
      Files.walk(path).use { walk ->
        walk.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
      }
    }
  }
}
