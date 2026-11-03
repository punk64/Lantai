package org.lantai.infrastructure.upgrade

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.lantai.infrastructure.configuration.LantaiProperties
import org.lantai.infrastructure.runtime.RuntimeProperties
import org.lantai.infrastructure.runtime.install.RuntimeEnvironment
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import org.springframework.mock.web.MockMultipartFile
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Properties
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class UpgradeInspectProgressTest {
  @TempDir
  lateinit var dir: Path

  @Test
  fun `inspect reports rising progress across unzip and verify`() {
    val jar = buildJar(40)
    val digest = MessageDigest.getInstance("SHA-256").digest(jar).joinToString("") { "%02x".format(it) }
    val pkg = buildPackage(jar, digest)
    val service = newService()

    val seen = CopyOnWriteArrayList<UpgradeProgress>()
    val sampler =
      Thread {
        while (!Thread.currentThread().isInterrupted) {
          seen.add(service.progress())
          runCatching { Thread.sleep(2) }.onFailure { return@Thread }
        }
      }
    sampler.isDaemon = true
    sampler.start()

    val result = service.inspect(MockMultipartFile("file", "Lantai-0.3.22-upgrade.zip", "application/zip", pkg))

    sampler.interrupt()
    sampler.join(2000)

    assertThat(result.version).isEqualTo("0.3.22")
    assertThat(result.sha256).isEqualTo(digest)
    assertThat(result.sizeBytes).isEqualTo(jar.size.toLong())

    val active = seen.filter { !it.idle }
    val unzipPcts = active.filter { it.phase == "unzip" }.map { it.pct }.distinct().sorted()
    val verifyPcts = active.filter { it.phase == "verify" }.map { it.pct }.distinct().sorted()

    assertThat(unzipPcts).hasSizeGreaterThanOrEqualTo(3)
    assertThat(unzipPcts.first()).isLessThan(20)
    assertThat(unzipPcts.last()).isGreaterThan(20)
    assertThat(verifyPcts).isNotEmpty()
    assertThat(verifyPcts.last()).isGreaterThanOrEqualTo(78)

    val monotonic = active.map { it.pct }.windowed(2).all { it[1] >= it[0] }
    assertThat(monotonic).isTrue()
    assertThat(service.progress().phase).isEqualTo("done")
    assertThat(service.progress().pct).isEqualTo(100)
  }

  @Test
  fun `inspect keeps failed package out of staged dir and reports error`() {
    val jar = buildJar(2)
    val pkg = buildPackage(jar, "deadbeef")
    val service = newService()

    val thrown =
      runCatching {
        service.inspect(MockMultipartFile("file", "Lantai-0.3.22-upgrade.zip", "application/zip", pkg))
      }.exceptionOrNull()

    assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
    assertThat(thrown!!.message).contains("校验失败")
    assertThat(service.progress().phase).isEqualTo("error")
    assertThat(java.nio.file.Files.exists(dir.resolve("cfg").resolve("upgrade").resolve("staged"))).isFalse()
  }

  private fun newService(): UpgradeService {
    val props = LantaiProperties().apply { configDir = dir.resolve("cfg").toString() }
    val build = mockk<ObjectProvider<BuildProperties>>()
    every { build.getIfAvailable() } returns
      BuildProperties(Properties().apply { setProperty("version", "0.3.21") })
    return UpgradeService(RuntimeEnvironment(props, RuntimeProperties()), ObjectMapper(), build)
  }

  private fun buildJar(megabytes: Int): ByteArray {
    val payload = ByteArray(megabytes * 1024 * 1024)
    Random(11L).nextBytes(payload)
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zos ->
      zos.putNextEntry(ZipEntry("BOOT-INF/classes/org/lantai/payload.bin"))
      zos.write(payload)
      zos.closeEntry()
      zos.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
      zos.write("Manifest-Version: 1.0\r\nStart-Class: org.lantai.ApplicationKt\r\n\r\n".toByteArray())
      zos.closeEntry()
    }
    return out.toByteArray()
  }

  private fun buildPackage(
    jar: ByteArray,
    sha256: String,
  ): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zos ->
      zos.putNextEntry(ZipEntry("lantai-0.3.22.jar"))
      zos.write(jar)
      zos.closeEntry()
      zos.putNextEntry(ZipEntry("manifest.json"))
      zos.write(
        """{"product":"lantai","version":"0.3.22","builtAt":"2026-11-03T12:00:00Z","sha256":"$sha256","fileName":"lantai-0.3.22.jar","notes":"progress"}"""
          .toByteArray(),
      )
      zos.closeEntry()
    }
    return out.toByteArray()
  }
}
