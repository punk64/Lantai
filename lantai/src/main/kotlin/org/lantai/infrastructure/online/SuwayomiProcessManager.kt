package org.lantai.infrastructure.online

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Service
import java.io.File
import java.lang.ProcessBuilder.Redirect
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists

private val logger = KotlinLogging.logger {}

data class SuwayomiRuntimeStatus(
  val running: Boolean,
  val managed: Boolean,
  val version: String?,
  val port: Int,
  val endpoint: String,
  val jarPresent: Boolean,
  val jarPath: String,
  val home: String,
  val dataDir: String,
  val configPath: String,
  val message: String?,
)

@Service
class SuwayomiProcessManager(
  private val properties: OnlineProperties,
  private val configWriter: SuwayomiConfigWriter,
  private val client: SuwayomiClient,
) {
  private var process: Process? = null

  @PreDestroy
  fun shutdown() {
    val current = process ?: return
    logger.info { "Stopping managed Suwayomi process" }
    current.destroy()
    if (!current.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
      current.destroyForcibly()
    }
    process = null
  }

  fun jarPresent(): Boolean = configWriter.jarPath().exists()

  fun portOpen(): Boolean =
    try {
      Socket().use { it.connect(InetSocketAddress("127.0.0.1", configWriter.endpointPort()), 800) }
      true
    } catch (e: Exception) {
      false
    }

  fun status(): SuwayomiRuntimeStatus {
    val credentials = configWriter.readCredentials()
    if (credentials != null && credentials.username.isNotBlank()) {
      properties.suwayomiUsername = credentials.username
      properties.suwayomiPassword = credentials.password
    }
    val about = if (portOpen()) runCatching { client.about() }.getOrNull() else null
    val managed = process?.isAlive == true
    val running = about != null
    val message =
      when {
        !jarPresent() -> "Suwayomi jar not installed"
        !portOpen() -> "Suwayomi is not listening on port ${configWriter.endpointPort()}"
        about == null -> "Port is busy but Suwayomi did not answer authentication"
        else -> null
      }
    return SuwayomiRuntimeStatus(
      running = running,
      managed = managed,
      version = about?.version,
      port = configWriter.endpointPort(),
      endpoint = properties.suwayomiEndpoint,
      jarPresent = jarPresent(),
      jarPath = configWriter.jarPath().toString(),
      home = configWriter.home().toString(),
      dataDir = configWriter.dataDir().toString(),
      configPath = configWriter.serverConf().toString(),
      message = message,
    )
  }

  fun ensureRunning(): SuwayomiRuntimeStatus {
    val credentials = configWriter.ensureConfig()
    properties.suwayomiUsername = credentials.username
    properties.suwayomiPassword = credentials.password
    if (properties.suwayomiDownloadsDir.isBlank()) {
      properties.suwayomiDownloadsDir = configWriter.effectiveDownloadsDir().toString()
    }

    if (portOpen()) {
      val about = runCatching { client.about() }.getOrNull()
      if (about != null) return status()
      throw SuwayomiException(
        "Port ${credentials.port} is occupied by a process that rejects the configured credentials; " +
          "stop it manually or rotate the password",
      )
    }

    if (!jarPresent()) throw SuwayomiException("Suwayomi jar not found at ${configWriter.jarPath()}")
    return start()
  }

  fun start(): SuwayomiRuntimeStatus {
    if (portOpen()) return status()
    val jar = configWriter.jarPath()
    if (!jar.exists()) throw SuwayomiException("Suwayomi jar not found at $jar")
    if (process?.isAlive == true) throw SuwayomiException("Suwayomi is already managed and running")

    val logFile = prepareLogFile()
    val command =
      listOf(
        javaExecutable(),
        "-Xmx1g",
        "-Dsuwayomi.tachidesk.config.server.rootDir=${configWriter.dataDir()}",
        "-jar",
        jar.toString(),
      )
    logger.info { "Starting Suwayomi: ${command.joinToString(" ")}" }
    val builder =
      ProcessBuilder(command)
        .directory(configWriter.home().toFile())
        .redirectErrorStream(true)
        .redirectOutput(Redirect.appendTo(logFile))
    process = builder.start()

    val deadline = System.currentTimeMillis() + properties.suwayomiLaunchTimeoutSeconds * 1000L
    while (System.currentTimeMillis() < deadline) {
      if (process?.isAlive != true) {
        val tail = tailLog(logFile)
        process = null
        throw SuwayomiException("Suwayomi exited during startup. Last output: $tail")
      }
      if (runCatching { client.about() }.isSuccess) {
        logger.info { "Suwayomi is ready on port ${configWriter.endpointPort()}" }
        return status()
      }
      Thread.sleep(1500)
    }
    throw SuwayomiException("Suwayomi did not become ready within ${properties.suwayomiLaunchTimeoutSeconds}s")
  }

  fun stop(): SuwayomiRuntimeStatus {
    val current = process
    if (current == null || !current.isAlive) {
      process = null
      return status()
    }
    current.destroy()
    if (!current.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) current.destroyForcibly()
    process = null
    val deadline = System.currentTimeMillis() + 10_000
    while (System.currentTimeMillis() < deadline && portOpen()) Thread.sleep(300)
    return status()
  }

  fun restart(): SuwayomiRuntimeStatus {
    stop()
    return ensureRunning()
  }

  private fun prepareLogFile(): File {
    val dir: Path = configWriter.dataDir().resolve("logs")
    Files.createDirectories(dir)
    val file = dir.resolve("lantai-launch.log").toFile()
    if (file.exists() && file.length() > 4 * 1024 * 1024) file.delete()
    return file
  }

  private fun tailLog(file: File): String =
    runCatching {
      val lines = file.readLines()
      lines.takeLast(15).joinToString(" | ")
    }.getOrDefault("")

  private fun javaExecutable(): String {
    val configured = properties.suwayomiJavaPath
    if (configured.isNotBlank()) return configured
    val home = System.getProperty("java.home") ?: return "java"
    val exe = if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java"
    val candidate = Paths.get(home, "bin", exe)
    return if (candidate.exists()) candidate.toString() else "java"
  }
}
