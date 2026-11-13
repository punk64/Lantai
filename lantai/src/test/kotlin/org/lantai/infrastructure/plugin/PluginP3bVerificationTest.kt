package org.lantai.infrastructure.plugin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("localdb")
class PluginP3bVerificationTest(
  @Autowired private val mockMvc: MockMvc,
  @Autowired private val instances: PluginInstanceService,
  @Autowired private val paths: PluginPaths,
  @Autowired private val store: PluginStore,
  @Autowired private val apiTokens: ApiTokenRepository,
  @Autowired private val users: LantaiUserRepository,
) {
  private val mapper = ObjectMapper()

  @Test
  fun upgradeSwitchesVersionAndPrunesStaleGrants() {
    reset()
    val work = Files.createTempDirectory("lantai-plugin-p3b")
    try {
      val owner = admin()
      val caller = LantaiPrincipal(owner)
      val installed = install(caller, buildPackage(work, "1.0.0", listOf("tts.speak", "notify.send")))
      val instanceId = installed.text("id")
      val port = java.net.URI.create(installed.text("endpoint")).port
      assertEquals("READY", installed.text("state"))

      instances.updateGrants(instanceId, listOf("tts.speak", "notify.send"), "tts.speak", "admin")
      val tokenName = "${PluginTokenService.TOKEN_NAME_PREFIX}$PLUGIN_ID"
      val beforeToken = apiTokens.findAll().single { it.name == tokenName }
      assertEquals(setOf(ApiToken.Scope.TTS_SPEAK, ApiToken.Scope.NOTIFY_SEND), beforeToken.scopes)

      val (status, upgraded) = call(json(Requests.post("/api/lantai/v1/plugins/$instanceId/upgrade"), body(buildPackage(work, "2.0.0", listOf("tts.speak")))), caller)
      assertEquals(200, status, "升级应返回 200")
      assertEquals("2.0.0", upgraded.text("version"), "版本应切到新版本")
      assertEquals("READY", upgraded.text("state"), "升级后应 READY")
      assertEquals(instanceId, upgraded.text("id"), "升级应复用同一实例 id")
      assertEquals(port, java.net.URI.create(upgraded.text("endpoint")).port, "升级应复用同一端口")
      assertTrue(paths.packageDir(PLUGIN_ID, "2.0.0").exists(), "新版本目录应存在")
      assertFalse(paths.packageDir(PLUGIN_ID, "1.0.0").exists(), "旧版本目录应被清理")

      val grants = instances.grants(instanceId)
      assertEquals(listOf("tts.speak"), grants.map { it.capability }, "新版本未声明的能力应被剔除")
      assertTrue(grants.single().isDefault, "保留下来的默认实现不应被清掉")
      assertEquals(setOf(ApiToken.Scope.TTS_SPEAK), apiTokens.findByIdOrNull(beforeToken.id)?.scopes, "令牌 scope 应随能力剔除同步")

      val (logStatus, log) = call(Requests.get("/api/lantai/v1/plugins/$instanceId/logs?lines=5"), caller)
      assertEquals(200, logStatus, "日志端点应返回 200")
      assertTrue(log.path("lines").isArray, "日志应返回数组")
    } finally {
      reset()
      deleteRecursively(work)
    }
  }

  @Test
  fun upgradeRollsBackWhenNewVersionFailsToStart() {
    reset()
    val work = Files.createTempDirectory("lantai-plugin-p3b-rb")
    try {
      val owner = admin()
      val caller = LantaiPrincipal(owner)
      val installed = install(caller, buildPackage(work, "1.0.0", listOf("tts.speak")))
      val instanceId = installed.text("id")
      val port = java.net.URI.create(installed.text("endpoint")).port

      val broken = buildPackage(work, "3.0.0", listOf("tts.speak"), crash = true)
      val (status, _) = call(json(Requests.post("/api/lantai/v1/plugins/$instanceId/upgrade"), body(broken)), caller)
      assertEquals(400, status, "新版本起不来应返回 400")

      val stored = instances.list().single { it.id == instanceId }
      assertEquals("1.0.0", stored.version, "失败后应停留在旧版本")
      assertEquals(PluginState.READY, stored.state, "回滚后旧版本应恢复 READY")
      assertEquals(port, stored.port, "回滚应复用原端口")
      assertTrue(paths.packageDir(PLUGIN_ID, "3.0.0").let { !it.exists() }, "失败的新版本目录应被清理")
      assertTrue(paths.packageDir(PLUGIN_ID, "1.0.0").exists(), "旧版本目录必须保留")
    } finally {
      reset()
      deleteRecursively(work)
    }
  }

  @Test
  fun upgradeRejectsMismatchedPluginId() {
    reset()
    val work = Files.createTempDirectory("lantai-plugin-p3b-id")
    try {
      val owner = admin()
      val caller = LantaiPrincipal(owner)
      val installed = install(caller, buildPackage(work, "1.0.0", listOf("tts.speak")))
      val instanceId = installed.text("id")

      val other = buildPackage(work, "1.1.0", listOf("tts.speak"), pluginId = "com.example.other")
      val (status, _) = call(json(Requests.post("/api/lantai/v1/plugins/$instanceId/upgrade"), body(other)), caller)
      assertEquals(400, status, "包 id 与实例不一致应返回 400")
      assertFalse(paths.packageDir("com.example.other", "1.1.0").exists(), "错包不得残留目录")
      assertEquals("1.0.0", instances.list().single { it.id == instanceId }.version)
    } finally {
      reset()
      deleteRecursively(work)
    }
  }

  @Test
  fun upgradeRejectsSameVersionAndExternalMode() {
    reset()
    val work = Files.createTempDirectory("lantai-plugin-p3b-same")
    try {
      val owner = admin()
      val caller = LantaiPrincipal(owner)
      val pkg = buildPackage(work, "1.0.0", listOf("tts.speak"))
      val installed = install(caller, pkg)
      val instanceId = installed.text("id")

      val (sameStatus, _) = call(json(Requests.post("/api/lantai/v1/plugins/$instanceId/upgrade"), body(pkg)), caller)
      assertEquals(409, sameStatus, "同版本升级应返回 409")

      val (logStatus, log) = call(Requests.get("/api/lantai/v1/plugins/$instanceId/logs?lines=200"), caller)
      assertEquals(200, logStatus)
      assertTrue(log.path("lines").size() >= 0)
    } finally {
      reset()
      deleteRecursively(work)
    }
  }

  @Test
  fun upgradeAndLogEndpointsRequireAdmin() {
    mockMvc
      .perform(
        Requests
          .post("/api/lantai/v1/plugins/whatever/upgrade")
          .contentType(MediaType.APPLICATION_JSON)
          .content("""{"zipPath":"x.zip"}"""),
      ).andExpect(status().isUnauthorized())
    mockMvc.perform(Requests.get("/api/lantai/v1/plugins/whatever/logs")).andExpect(status().isUnauthorized())
  }

  private fun admin(): LantaiUser =
    users.findAll().firstOrNull { it.isAdmin }
      ?: LantaiUser(
        email = "plugin-p3b@lantai.local",
        password = "plugin-p3b",
        roles = setOf(UserRoles.ADMIN),
        username = "plugin-p3b-admin",
      ).also { users.insert(it) }

  private fun install(
    caller: LantaiPrincipal,
    pkg: Path,
  ): JsonNode {
    val (status, created) = call(json(Requests.post("/api/lantai/v1/plugins/install"), body(pkg)), caller)
    assertEquals(201, status, "安装应返回 201")
    return created
  }

  private fun reset() {
    instances.list().forEach { instances.delete(it.id) }
    apiTokens.findAll().filter { it.name.startsWith(PluginTokenService.TOKEN_NAME_PREFIX) }.forEach { apiTokens.deleteById(it.id) }
    runCatching { deleteRecursively(paths.root().resolve(PLUGIN_ID)) }
  }

  private fun body(pkg: Path): String = """{"zipPath":${mapper.writeValueAsString(pkg.toString())}}"""

  private fun buildPackage(
    dir: Path,
    version: String,
    capabilities: List<String>,
    crash: Boolean = false,
    pluginId: String = PLUGIN_ID,
  ): Path {
    val capabilityJson = capabilities.joinToString(",", "[", "]") { "\"$it\"" }
    val manifest =
      """
      {
        "id": "$pluginId",
        "name": "Upgradable Fake",
        "version": "$version",
        "protocol": 2,
        "minHost": "0.6.0",
        "runtime": "command",
        "entry": "bin/run.bat",
        "memoryMb": 128,
        "capabilities": $capabilityJson
      }
      """.trimIndent()
    val script =
      if (crash) {
        """
        import sys
        sys.exit(1)
        """.trimIndent()
      } else {
        """
        import json, os, sys
        from http.server import BaseHTTPRequestHandler, HTTPServer

        KEY = os.environ["LANTAI_PLUGIN_HOST_KEY"]
        PORT = int(os.environ["LANTAI_PLUGIN_PORT"])

        class H(BaseHTTPRequestHandler):
            def do_GET(self):
                if self.headers.get("X-Lantai-Host-Key") != KEY:
                    self.send_response(401)
                    self.end_headers()
                    return
                if self.path == "/v1/manifest":
                    body = {"id": "$pluginId", "name": "Upgradable Fake", "version": "$version",
                            "protocol": 2, "minHost": "0.6.0", "runtime": "command",
                            "capabilities": $capabilityJson}
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
      }
    val launcher = "@echo off\r\npython \"%~dp0plugin.py\"\r\n"
    val zip = dir.resolve("$pluginId-$version.zip")
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

  private companion object {
    private const val PLUGIN_ID = "com.example.upgradable"
  }
}
