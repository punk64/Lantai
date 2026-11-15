package org.lantai.infrastructure.upgrade

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class UpgradeBackupPruneTest {
  @TempDir
  lateinit var dir: Path

  @Test
  fun `prune keeps only the requested backup`() {
    val keep = dir.resolve("lantai-0.3.16.jar")
    Files.writeString(keep, "new")
    Files.writeString(dir.resolve("lantai-0.3.10.jar"), "old")
    Files.writeString(dir.resolve("lantai-0.3.15.jar"), "old")
    Files.writeString(dir.resolve("lantai-0.3.15.jar.tmp"), "half")

    val removed = UpgradeService.pruneBackups(dir, keep)

    assertThat(removed).isEqualTo(3)
    assertThat(Files.list(dir).use { it.toList() }.map { it.fileName.toString() }).containsExactly("lantai-0.3.16.jar")
  }

  @Test
  fun `prune leaves unrelated files untouched`() {
    val keep = dir.resolve("lantai-0.3.16.jar")
    Files.writeString(keep, "new")
    Files.writeString(dir.resolve("notes.txt"), "keep me")
    Files.createDirectory(dir.resolve("sub"))

    val removed = UpgradeService.pruneBackups(dir, keep)

    assertThat(removed).isZero()
    assertThat(Files.exists(dir.resolve("notes.txt"))).isTrue()
    assertThat(Files.exists(dir.resolve("sub"))).isTrue()
  }

  @Test
  fun `prune on missing directory is a no-op`() {
    assertThat(UpgradeService.pruneBackups(dir.resolve("absent"), dir.resolve("absent/lantai-1.jar"))).isZero()
  }

  @Test
  fun `prune when keep is absent clears every jar`() {
    Files.writeString(dir.resolve("lantai-0.3.10.jar"), "old")
    Files.writeString(dir.resolve("lantai-0.3.11.jar"), "old")

    val removed = UpgradeService.pruneBackups(dir, dir.resolve("lantai-0.3.12.jar"))

    assertThat(removed).isEqualTo(2)
    assertThat(Files.list(dir).use { it.toList() }).isEmpty()
  }

  @Test
  fun `backup version is exposed when it differs from the running one`() {
    assertThat(UpgradeService.backupVersion(dir.resolve("lantai-0.3.16.jar"), "0.3.17")).isEqualTo("0.3.16")
  }

  @Test
  fun `backup version is hidden after rolling back onto it`() {
    assertThat(UpgradeService.backupVersion(dir.resolve("lantai-0.3.17.jar"), "0.3.17")).isNull()
  }

  @Test
  fun `backup version is null without a backup file`() {
    assertThat(UpgradeService.backupVersion(null, "0.3.17")).isNull()
  }
}
