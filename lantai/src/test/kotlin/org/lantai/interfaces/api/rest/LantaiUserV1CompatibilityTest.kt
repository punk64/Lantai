package org.lantai.interfaces.api.rest

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.web.bind.annotation.RequestMapping

@SpringBootTest
@AutoConfigureMockMvc
class LantaiUserV1CompatibilityTest {
  @Autowired
  private lateinit var mockMvc: MockMvc

  @Test
  fun `user controller declares both v1 and v2 base paths`() {
    val mapping = UserController::class.java.getAnnotation(RequestMapping::class.java)
    assertThat(mapping).isNotNull
    assertThat(mapping.value).contains("api/v1/users", "api/v2/users")
  }

  @Test
  fun `anonymous access to v1 and v2 user paths behaves the same`() {
    val v1 = mockMvc.get("/api/v1/users/me").andReturn().response.status
    val v2 = mockMvc.get("/api/v2/users/me").andReturn().response.status
    assertThat(v1).isEqualTo(401)
    assertThat(v2).isEqualTo(v1)
  }
}
