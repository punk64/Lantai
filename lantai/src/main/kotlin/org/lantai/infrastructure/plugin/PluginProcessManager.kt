package org.lantai.infrastructure.plugin

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PreDestroy
import org.lantai.domain.model.PluginInstance
import org.lantai.domain.model.PluginState
import org.lantai.domain.persistence.PluginStore
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.File
import java.lang.ProcessBuilder.Redirect
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists

private val logger = KotlinLogging.logger {}

@Service
class PluginProcessManager(
  private val store: PluginStore,
  private val httpClient: PluginHttpClient,
  private val paths: PluginPaths,
  private val tokenService: PluginTokenService,
  @param:Value($$"${server.port:8080}") private val hostPort: Int,
) {
  private val processes = ConcurrentHashMap<String, Process>()

  fun allocatePort(): Int {
    val used = store.findAllInstances().mapNotNull { it.port }.toSet()
    for (port in PORT_RANGE) {
      if (port in used) continue
      if (!portFree(port)) continue
      return port
    }
    throw PluginRequestException("插件端口区间 $PORT_MIN-$PORT_MAX 已无可用端口")
  }

  fun start(
    instance: PluginInstance,
    onWait: ((Long) -> Unit)? = null,
  ): PluginInstance {
    val alive = processes[instance.id]?.takeIf { it.isAlive }
    if (alive != null) return instance.copy(pid = alive.pid().toInt())
    processes.remove(instance.id)
    val port = instance.port ?: throw PluginRequestException("插件未分配端口，无法启动")
    val home = Paths.get(instance.homeDir).toAbsolutePath().normalize()
    if (!home.startsWith(paths.root())) throw PluginRequestException("插件目录不在受管根目录内：${instance.homeDir}")
    if (!Files.isDirectory(home)) throw PluginRequestException("插件目录不存在：${instance.homeDir}")
    val command = commandOf(instance, home)
    val logFile = prepareLogFile(home)
    val builder =
      ProcessBuilder(command)
        .directory(home.toFile())
        .redirectErrorStream(true)
        .redirectOutput(Redirect.appendTo(logFile))
    applyEnvironment(builder.environment(), instance, home, port)
    logger.info { "Starting plugin ${instance.pluginId}: ${command.joinToString(" ")}" }
    val process =
      runCatching { builder.start() }.getOrElse {
        throw PluginRequestException("插件进程启动失败：${it.message ?: it::class.simpleName}")
      }
    processes[instance.id] = process
    val startedAt = System.currentTimeMillis()
    val deadline = startedAt + START_TIMEOUT_MS
    var reportedAt = 0L
    while (System.currentTimeMillis() < deadline) {
      if (!process.isAlive) {
        processes.remove(instance.id)
        killTree(process)
        throw PluginRequestException("插件进程启动后立即退出：${tailLog(logFile)}")
      }
      if (portOpen(port)) {
        logger.info { "Plugin ${instance.pluginId} is listening on $port" }
        return instance.copy(pid = process.pid().toInt())
      }
      val elapsed = System.currentTimeMillis() - startedAt
      if (onWait != null && elapsed - reportedAt >= WAIT_REPORT_INTERVAL_MS) {
        reportedAt = elapsed
        onWait(elapsed / 1000)
      }
      Thread.sleep(POLL_INTERVAL_MS)
    }
    processes.remove(instance.id)
    killTree(process)
    throw PluginRequestException("插件在 ${START_TIMEOUT_MS / 1000}s 内未监听端口 $port：${tailLog(logFile)}")
  }

  fun stop(instanceId: String) {
    val current = processes.remove(instanceId) ?: return
    logger.info { "Stopping plugin process $instanceId" }
    killTree(current)
  }

  private fun killTree(process: Process) {
    val children = runCatching { process.descendants().toList() }.getOrDefault(emptyList())
    if (process.isAlive) process.destroy()
    children.forEach { if (it.isAlive) it.destroy() }
    if (process.isAlive && !process.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly()
    children.forEach { if (it.isAlive) it.destroyForcibly() }
  }

  fun isRunning(instanceId: String): Boolean = processes[instanceId]?.isAlive == true

  fun probe(instance: PluginInstance): PluginState {
    val endpoint = instance.endpoint?.takeIf { it.isNotBlank() } ?: return PluginState.UNAVAILABLE
    return httpClient.handshake(endpoint, instance.hostKey).state
  }

  @PreDestroy
  fun shutdown() {
    processes.keys.toList().forEach { stop(it) }
  }

  private fun commandOf(
    instance: PluginInstance,
    home: Path,
  ): List<String> {
    val entry = instance.entry.takeIf { it.isNotBlank() }
    return when (instance.runtimeKind.lowercase()) {
      RUNTIME_COMMAND -> scriptCommand(home, entry)
      else -> {
        val jar = home.resolve(entry ?: "bin/plugin.jar").normalize()
        if (!jar.exists()) throw PluginRequestException("插件包缺失：$jar")
        val heap = instance.memoryMb.takeIf { it > 0 } ?: DEFAULT_MEMORY_MB
        listOf(javaExecutable(), "-Xmx${heap}m", "-jar", jar.toString())
      }
    }
  }

  private fun scriptCommand(
    home: Path,
    entry: String?,
  ): List<String> {
    val windows = System.getProperty("os.name").lowercase().contains("win")
    val script = home.resolve(entry ?: if (windows) "bin/run.bat" else "bin/run.sh").normalize()
    if (!script.exists()) throw PluginRequestException("插件入口缺失：$script")
    return if (windows) listOf("cmd", "/c", script.toString()) else listOf("sh", script.toString())
  }

  private fun applyEnvironment(
    env: MutableMap<String, String>,
    instance: PluginInstance,
    home: Path,
    port: Int,
  ) {
    env["LANTAI_PLUGIN_ID"] = instance.pluginId
    env["LANTAI_PLUGIN_PORT"] = port.toString()
    env["LANTAI_PLUGIN_HOME"] = home.toString()
    env["LANTAI_PLUGIN_DATA"] = home.parent.resolve("data").toString()
    env["LANTAI_PLUGIN_BASE"] = "http://127.0.0.1:$hostPort"
    env["LANTAI_PLUGIN_SETTINGS"] = instance.settings
    instance.hostKey?.takeIf { it.isNotBlank() }?.let { env["LANTAI_PLUGIN_HOST_KEY"] = it }
    tokenService.valueOf(instance)?.let { env["LANTAI_PLUGIN_TOKEN"] = it }
  }

  private fun prepareLogFile(home: Path): File {
    val dir = home.resolve("logs")
    Files.createDirectories(dir)
    val file = dir.resolve("plugin.log").toFile()
    if (file.exists() && file.length() > MAX_LOG_BYTES) file.delete()
    return file
  }

  private fun tailLog(file: File): String =
    runCatching { file.readLines().takeLast(12).joinToString(" | ") }.getOrDefault("")

  private fun portFree(port: Int): Boolean =
    runCatching {
      ServerSocket().use { socket ->
        socket.reuseAddress = true
        socket.bind(InetSocketAddress("127.0.0.1", port), 1)
      }
    }.isSuccess

  private fun portOpen(port: Int): Boolean =
    runCatching {
      Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), PORT_PROBE_TIMEOUT_MS) }
    }.isSuccess

  private fun javaExecutable(): String {
    val home = System.getProperty("java.home") ?: return "java"
    val exe = if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java"
    val candidate = Paths.get(home, "bin", exe)
    return if (candidate.exists()) candidate.toString() else "java"
  }

  companion object {
    const val RUNTIME_JVM = "jvm"
    const val RUNTIME_COMMAND = "command"
    const val PORT_MIN = 26000
    const val PORT_MAX = 26999
    const val DEFAULT_MEMORY_MB = 256
    private val PORT_RANGE = PORT_MIN..PORT_MAX
    private const val START_TIMEOUT_MS = 15_000L
    private const val POLL_INTERVAL_MS = 400L
    private const val WAIT_REPORT_INTERVAL_MS = 1_500L
    private const val PORT_PROBE_TIMEOUT_MS = 300
    private const val STOP_TIMEOUT_SECONDS = 5L
    private const val MAX_LOG_BYTES = 4L * 1024 * 1024
  }
}
