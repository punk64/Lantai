package org.lantai.infrastructure.online

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "LANTAI_SUWAYOMI_HOME", matches = ".+")
class SuwayomiAdminServiceTest {
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
  private lateinit var adminService: SuwayomiAdminService

  @Test
  fun `reports running instance with supported version`() {
    val status = adminService.status()

    assertThat(status.running).isTrue()
    assertThat(status.version).isNotNull()
    assertThat(status.version).startsWith("v2")
    assertThat(status.supported).isTrue()
    assertThat(status.port).isEqualTo(25610)
  }

  @Test
  fun `reads settings through graphql`() {
    val settings = adminService.settings()

    assertThat(settings).containsKeys("downloadAsCbz", "maxSourcesInParallel", "globalUpdateInterval")
    assertThat(settings["downloadAsCbz"]).isEqualTo(true)
  }

  @Test
  fun `loads catalog from remote index`() {
    val page = adminService.catalog("kuaikan", null, false, 0, 20, true, null)

    assertThat(page.total).isGreaterThan(0)
    assertThat(page.items).isNotEmpty()
    assertThat(page.items.any { it.pkgName.contains("kuaikan") }).isTrue()
    assertThat(page.items.all { it.name.isNotBlank() }).isTrue()
  }

  @Test
  fun `filters catalog by language`() {
    val page = adminService.catalog(null, "zh", false, 0, 30, false, null)

    assertThat(page.total).isGreaterThan(0)
    assertThat(page.items).isNotEmpty()
    assertThat(page.items.all { it.lang.contains("zh") }).isTrue()
  }

  @Test
  fun `lists extension stores`() {
    val stores = adminService.stores()

    assertThat(stores).isNotEmpty()
    assertThat(stores.any { (it.name ?: "").contains("Keiyoushi", ignoreCase = true) }).isTrue()
  }
}
