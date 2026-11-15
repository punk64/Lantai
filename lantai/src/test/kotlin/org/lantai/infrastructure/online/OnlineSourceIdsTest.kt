package org.lantai.infrastructure.online

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OnlineSourceIdsTest {
  @Test
  fun `plugin source id carries plugin and remote id`() {
    val id = OnlineSourceIds.pluginSourceId("9k2x", "mangadex")
    assertThat(id).isEqualTo("plugin:9k2x:mangadex")
    assertThat(OnlineSourceIds.kindOf(id)).isEqualTo("plugin:9k2x")
    assertThat(OnlineSourceIds.remoteSourceId(id)).isEqualTo("mangadex")
    assertThat(OnlineSourceIds.isPlugin(id)).isTrue()
  }

  @Test
  fun `bare source id stays on the default kind`() {
    assertThat(OnlineSourceIds.kindOf("1234")).isEqualTo("plugin")
    assertThat(OnlineSourceIds.remoteSourceId("1234")).isEqualTo("1234")
    assertThat(OnlineSourceIds.isPlugin("1234")).isFalse()
  }

  @Test
  fun `missing source id falls back to the default kind`() {
    assertThat(OnlineSourceIds.kindOf(null)).isEqualTo("plugin")
    assertThat(OnlineSourceIds.kindOf("")).isEqualTo("plugin")
    assertThat(OnlineSourceIds.isPlugin(null)).isFalse()
  }

  @Test
  fun `kind keeps only the plugin segment`() {
    assertThat(OnlineSourceIds.pluginKind("9k2x")).isEqualTo("plugin:9k2x")
    assertThat(OnlineSourceIds.kindOf("plugin:9k2x:mangadex")).isEqualTo("plugin:9k2x")
  }

  @Test
  fun `remote id keeps trailing segments`() {
    assertThat(OnlineSourceIds.remoteSourceId("plugin:9k2x:a:b")).isEqualTo("a:b")
  }

  @Test
  fun `plugin prefix alone is not a plugin source`() {
    assertThat(OnlineSourceIds.kindOf("plugin")).isEqualTo("plugin")
    assertThat(OnlineSourceIds.isPlugin("plugin")).isFalse()
  }
}
