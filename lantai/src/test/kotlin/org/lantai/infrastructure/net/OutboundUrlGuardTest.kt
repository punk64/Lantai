package org.lantai.infrastructure.net

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class OutboundUrlGuardTest {
  @Test
  fun `given loopback in any notation when checking then rejected`() {
    listOf(
      "127.0.0.1",
      "127.1",
      "127.0.0.1.",
      "2130706433",
      "0x7f000001",
      "0x7f.0.0.1",
      "localhost",
      "app.localhost",
      "0.0.0.0",
      "::1",
      "[::1]",
      "fe80::1",
      "fc00::1",
      "::ffff:127.0.0.1",
    ).forEach { assertThat(OutboundUrlGuard.isPrivateHost(it)).describedAs(it).isTrue() }
  }

  @Test
  fun `given private ranges when checking then rejected`() {
    listOf(
      "10.0.0.1",
      "172.16.0.1",
      "172.31.255.255",
      "192.168.1.1",
      "192.0.0.1",
      "169.254.1.1",
      "100.64.0.1",
    ).forEach { assertThat(OutboundUrlGuard.isPrivateHost(it)).describedAs(it).isTrue() }
  }

  @Test
  fun `given public address when checking then allowed`() {
    listOf("8.8.8.8", "1.1.1.1", "172.32.0.1", "example.com", "img.example.com").forEach {
      assertThat(OutboundUrlGuard.isPrivateHost(it)).describedAs(it).isFalse()
    }
  }

  @Test
  fun `given numeric ip when parsing url then rejected`() {
    listOf("http://2130706433/cover.jpg", "http://0x7f000001/cover.jpg", "http://127.1/cover.jpg", "http://[::1]/cover.jpg").forEach {
      assertThatThrownBy { OutboundUrlGuard.parse(it) }.describedAs(it).isInstanceOf(IllegalArgumentException::class.java)
    }
  }

  @Test
  fun `given public url when parsing then accepted`() {
    assertThat(OutboundUrlGuard.parse("https://img.example.com/cover.jpg").host).isEqualTo("img.example.com")
  }
}
