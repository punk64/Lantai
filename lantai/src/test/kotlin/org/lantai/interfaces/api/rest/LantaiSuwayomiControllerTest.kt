package org.lantai.interfaces.api.rest

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "LANTAI_SUWAYOMI_HOME", matches = ".+")
class LantaiSuwayomiControllerTest {
  companion object {
    @JvmStatic
    @DynamicPropertySource
    fun properties(registry: DynamicPropertyRegistry) {
      registry.add("lantai.online.enabled") { "true" }
      registry.add("lantai.online.suwayomi-home") { System.getenv("LANTAI_SUWAYOMI_HOME") }
      registry.add("lantai.online.suwayomi-auto-start") { "false" }
    }
  }

  @Autowired
  private lateinit var mockMvc: MockMvc

  @Test
  fun `rejects anonymous access`() {
    mockMvc.get("/api/lantai/v1/suwayomi/status").andExpect {
      status { isUnauthorized() }
    }
  }

  @Test
  @WithMockUser(username = "admin@lantai.local", roles = ["ADMIN"])
  fun `serves status`() {
    mockMvc.get("/api/lantai/v1/suwayomi/status").andExpect {
      status { isOk() }
      jsonPath("$.running") { value(true) }
      jsonPath("$.supported") { value(true) }
      jsonPath("$.port") { value(25610) }
    }
  }

  @Test
  @WithMockUser(username = "admin@lantai.local", roles = ["ADMIN"])
  fun `serves settings`() {
    mockMvc.get("/api/lantai/v1/suwayomi/settings").andExpect {
      status { isOk() }
      jsonPath("$.downloadAsCbz") { value(true) }
    }
  }

  @Test
  @WithMockUser(username = "admin@lantai.local", roles = ["ADMIN"])
  fun `serves installed extensions`() {
    mockMvc.get("/api/lantai/v1/suwayomi/extensions").andExpect {
      status { isOk() }
      jsonPath("$") { isArray() }
    }
  }

  @Test
  @WithMockUser(username = "admin@lantai.local", roles = ["ADMIN"])
  fun `serves extension catalog`() {
    mockMvc.get("/api/lantai/v1/suwayomi/extensions/catalog") {
      param("lang", "zh")
      param("limit", "10")
    }.andExpect {
      status { isOk() }
      jsonPath("$.total") { isNumber() }
      jsonPath("$.items") { isArray() }
    }
  }

  @Test
  @WithMockUser(username = "admin@lantai.local", roles = ["ADMIN"])
  fun `serves extension stores`() {
    mockMvc.get("/api/lantai/v1/suwayomi/stores").andExpect {
      status { isOk() }
      jsonPath("$") { isArray() }
    }
  }
}
