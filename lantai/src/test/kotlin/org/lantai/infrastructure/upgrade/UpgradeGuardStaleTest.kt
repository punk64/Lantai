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
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class UpgradeGuardStaleTest {
  @TempDir
  lateinit var dir: Path

  @Test
  fun `stuck guard is replaced so a new upload is accepted`() {
    val jar = buildJar(2)
    val digest = MessageDigest.getInstance("SHA-256").digest(jar).joinToString("") { "%02x".format(it) }
    val pkg = buildPackage(jar, digest)
    val service = newService()

    val held = guardOf(service)
    assertThat(held.tryAcquire()).isTrue()
    tickOf(service).set(System.currentTimeMillis() - 300_000)

    var failure: Throwable? = null
    val worker =
      Thread {
        runCatching {
          service.inspect(MockMultipartFile("file", "Lantai-0.3.22-upgrade.zip", "application/zip", pkg))
        }.onFailure { failure = it }
      }
    worker.start()
    worker.join(10000)

    assertThat(failure).isNull()
    assertThat(awaitInspect(service).phase).isEqualTo("done")
  }

  @Test
  fun `fresh guard still rejects a second upload`() {
    val jar = buildJar(2)
    val digest = MessageDigest.getInstance("SHA-256").digest(jar).joinToString("") { "%02x".format(it) }
    val pkg = buildPackage(jar, digest)
    val service = newService()

    val held = guardOf(service)
    assertThat(held.tryAcquire()).isTrue()
    tickOf(service).set(System.currentTimeMillis())

    var failure: Throwable? = null
    val worker =
      Thread {
        runCatching {
          service.inspect(MockMultipartFile("file", "Lantai-0.3.22-upgrade.zip", "application/zip", pkg))
        }.onFailure { failure = it }
      }
    worker.start()
    worker.join(10000)

    assertThat(failure).isNotNull()
    assertThat(failure!!.message).contains("上一个升级包仍在校验")
  }

  @Test
  fun `accepted sequence matches reported progress`() {
    val jar = buildJar(2)
    val digest = MessageDigest.getInstance("SHA-256").digest(jar).joinToString("") { "%02x".format(it) }
    val pkg = buildPackage(jar, digest)
    val service = newService()

    val first =
      service.inspect(MockMultipartFile("file", "Lantai-0.3.22-upgrade.zip", "application/zip", pkg))
    val finished = awaitInspect(service)
    assertThat(finished.seq).isEqualTo(first.seq)
    assertThat(first.seq).isGreaterThan(0)

    val second =
      service.inspect(MockMultipartFile("file", "Lantai-0.3.22-upgrade.zip", "application/zip", pkg))
    assertThat(second.seq).isGreaterThan(first.seq)
    assertThat(awaitInspect(service, second.seq).seq).isEqualTo(second.seq)
  }

  private fun guardOf(service: UpgradeService): Semaphore =
    UpgradeService::class.java.getDeclaredField("inspectGuard").apply { isAccessible = true }.get(service) as Semaphore

  private fun tickOf(service: UpgradeService): AtomicLong =
    UpgradeService::class.java.getDeclaredField("inspectTick").apply { isAccessible = true }.get(service) as AtomicLong

  private fun awaitInspect(
    service: UpgradeService,
    seq: Long = 0,
    timeoutMs: Long = 20000,
  ): UpgradeProgress {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      val progress = service.progress()
      if ((progress.phase == "done" || progress.phase == "error") && (seq == 0L || progress.seq == seq)) return progress
      Thread.sleep(5)
    }
    return service.progress()
  }

  private fun newService(): UpgradeService {
    val props = LantaiProperties().apply { configDir = dir.resolve("cfg").toString() }
    val build = mockk<ObjectProvider<BuildProperties>>()
    every { build.getIfAvailable() } returns
      BuildProperties(Properties().apply { setProperty("version", "0.3.21") })
    return UpgradeService(RuntimeEnvironment(props, RuntimeProperties()), ObjectMapper(), build, mockk(relaxed = true))
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
        """{"product":"lantai","version":"0.3.22","builtAt":"2026-11-03T12:00:00Z","sha256":"$sha256","fileName":"lantai-0.3.22.jar","notes":"stale"}"""
          .toByteArray(),
      )
      zos.closeEntry()
    }
    return out.toByteArray()
  }
}
