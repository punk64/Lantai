package org.lantai.infrastructure.upgrade

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class UpgradeVersionTest {
  @Test
  fun `patch numbers compare numerically not lexically`() {
    assertThat(compareVersions("0.3.10", "0.3.9")).isPositive()
    assertThat(compareVersions("0.3.9", "0.3.10")).isNegative()
  }

  @Test
  fun `equal versions compare zero regardless of trailing zeros`() {
    assertThat(compareVersions("0.3.8", "0.3.8")).isZero()
    assertThat(compareVersions("0.3", "0.3.0.0")).isZero()
  }

  @Test
  fun `prefix and suffix are stripped before comparing`() {
    assertThat(compareVersions("v0.3.8", "0.3.8")).isZero()
    assertThat(compareVersions("0.3.8-SNAPSHOT", "0.3.8")).isZero()
    assertThat(compareVersions("0.4.0-rc1", "0.3.8")).isPositive()
  }

  @Test
  fun `unknown current version never blocks an upgrade`() {
    assertThat(compareVersions("0.3.8", "unknown")).isPositive()
  }

  @Test
  fun `downgrade is detected`() {
    assertThat(compareVersions("0.3.7", "0.3.8")).isNegative()
    assertThat(compareVersions("0.2.11", "0.3.0")).isNegative()
  }
}
