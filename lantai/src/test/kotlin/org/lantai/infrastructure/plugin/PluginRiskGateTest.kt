package org.lantai.infrastructure.plugin

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Files

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("localdb")
class PluginRiskGateTest(
  @Autowired private val mockMvc: MockMvc,
  @Autowired private val paths: PluginPaths,
) {
  private val admin = user("admin").roles("ADMIN")

  @BeforeEach
  fun reset() {
    Files.deleteIfExists(paths.root().parent.resolve(".plugin-risk-ack"))
  }

  @Test
  fun unacknowledgedBlocksPluginApis() {
    mockMvc.perform(get("/api/lantai/v1/plugins").with(admin)).andExpect(status().isForbidden)
    mockMvc
      .perform(
        patch("/api/lantai/v1/plugins/NOPE")
          .with(admin)
          .contentType(MediaType.APPLICATION_JSON)
          .content("""{"enabled":true}"""),
      ).andExpect(status().isForbidden)
    mockMvc.perform(get("/api/lantai/v1/plugins/risk-ack").with(admin)).andExpect(status().isOk)
    mockMvc.perform(post("/api/lantai/v1/plugins/risk-ack").with(admin)).andExpect(status().isOk)
    mockMvc.perform(get("/api/lantai/v1/plugins").with(admin)).andExpect(status().isOk)
  }
}
