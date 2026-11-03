package org.lantai.infrastructure.runtime.install

import io.github.oshai.kotlinlogging.KotlinLogging
import org.lantai.infrastructure.configuration.LantaiSettingsProvider
import org.lantai.infrastructure.online.OnlineProperties
import org.lantai.infrastructure.online.SuwayomiConfigWriter
import org.lantai.infrastructure.online.SuwayomiException
import org.lantai.infrastructure.online.SuwayomiInstaller
import org.lantai.infrastructure.online.SuwayomiProcessManager
import org.lantai.infrastructure.online.SuwayomiSourceProvider
import org.lantai.infrastructure.runtime.RuntimeProperties
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.Comparator
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.exists
import kotlin.io.path.name

private val logger = KotlinLogging.logger {}

@Component
class SuwayomiInstallerAdapter(
  private val installer: SuwayomiInstaller,
  private val processManager: SuwayomiProcessManager,
  private val configWriter: SuwayomiConfigWriter,
  private val environment: RuntimeEnvironment,
  private val runtimeProperties: RuntimeProperties,
  private val onlineProperties: OnlineProperties,
  private val settingsProvider: LantaiSettingsProvider,
) : RuntimeInstaller,
  RuntimeToggle {
  override val kind: String = SuwayomiSourceProvider.KIND

  override val displayName: String = "Suwayomi"

  private val state = AtomicReference(RuntimeProgress(kind = kind))

  override fun detect(): RuntimeDetection {
    val home = configWriter.home()
    val jar = configWriter.jarPath()
    val installed = jar.exists()
    val version = if (installed) runCatching { installer.installedVersion() }.getOrNull() else null
    val free = environment.freeSpace(home)
    val port = configWriter.endpointPort()
    val portOk = !processManager.portOpen() || environment.portFree(port).not()
    val checks =
      listOf(
        RuntimeCheck(
          name = "java",
          ok = true,
          detail = "JRE ${System.getProperty("java.version")}",
        ),
        RuntimeCheck(
          name = "directory",
          ok = environment.writable(home),
          detail = home.toString(),
          hint = "Directory is not writable, check the mounted volume permissions",
        ),
        RuntimeCheck(
          name = "package",
          ok = installed,
          detail = if (installed) jar.toString() else "not installed",
          hint = "Install Suwayomi from the official release or upload Suwayomi-Server.jar",
        ),
        RuntimeCheck(
          name = "disk",
          ok = free >= MIN_FREE_BYTES,
          detail = environment.humanBytes(free),
          hint = "Free at least ${environment.humanBytes(MIN_FREE_BYTES)} on the config volume",
        ),
        RuntimeCheck(
          name = "port",
          ok = portOk,
          detail = port.toString(),
          hint = "Port $port is used by another process",
        ),
        RuntimeCheck(
          name = "version",
          ok = installer.isSupported(version),
          detail = version ?: "unknown",
          hint = "Supported ${onlineProperties.suwayomiMinVersion} .. ${onlineProperties.suwayomiMaxVersion}",
        ),
      )
    val blockers = checks.filterNot { it.ok }.map { it.hint ?: it.name }
    return RuntimeDetection(
      kind = kind,
      platform = environment.platform(),
      installed = installed,
      enabled = isEnabled(),
      ready = installed && blockers.isEmpty(),
      checks = checks,
      blockers = blockers,
    )
  }

  override fun describe(): RuntimePackageInfo {
    val release = runCatching { installer.latestRelease() }.getOrNull()
    val fileName = release?.assetName ?: "Suwayomi-Server.jar"
    val sources = mutableListOf<RuntimeSource>()
    release?.assetUrl?.let { sources += RuntimeSource("GitHub releases", it, RuntimeSourceKind.OFFICIAL) }
    val mirror = runtimeProperties.mirror.takeIf { it.isNotBlank() }
    if (mirror != null && release?.assetUrl != null) {
      sources += RuntimeSource("configured mirror", prefix(mirror, release.assetUrl), RuntimeSourceKind.MIRROR)
    }
    localPackage()?.let { sources += RuntimeSource("local package", it.toString(), RuntimeSourceKind.LOCAL) }
    return RuntimePackageInfo(
      kind = kind,
      version = release?.tag?.removePrefix("v"),
      fileName = fileName,
      size = release?.size?.takeIf { it > 0 },
      sources = sources,
      notes =
        listOf(
          "Suwayomi reuses the Lantai JRE, no extra runtime is installed",
          "Downloaded chapters are stored under ${configWriter.dataDir()}",
        ),
    )
  }

  override fun accept(
    bytes: ByteArray,
    fileName: String?,
  ): RuntimePackage {
    val name = fileName?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "Suwayomi-Server.jar"
    val dir = environment.stagingDir(kind)
    Files.createDirectories(dir)
    val target = dir.resolve(name)
    val temp = dir.resolve("$name.download")
    Files.write(temp, bytes)
    installer.validatePackage(temp)
    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
    logger.info { "Accepted Suwayomi package $target (${bytes.size} bytes)" }
    return RuntimePackage(
      info = RuntimePackageInfo(kind = kind, version = null, fileName = name, size = bytes.size.toLong()),
      file = target,
      source = RuntimeSourceKind.UPLOAD,
    )
  }

  override fun install(installed: RuntimePackage): RuntimeProgress {
    emit(RuntimeInstallPhase.INSTALLING, 60, "installing ${installed.file.name}")
    try {
      installer.installFromFile(installed.file)
      val version = installer.installedVersion()
      if (!installer.isSupported(version)) {
        throw SuwayomiException("Suwayomi $version is outside the supported range")
      }
      enableAndStart()
      emit(RuntimeInstallPhase.DONE, 100, "installed", version)
      return state.get()
    } catch (e: Exception) {
      emit(RuntimeInstallPhase.FAILED, 0, e.message)
      throw if (e is SuwayomiException) e else SuwayomiException(e.message ?: "install failed", e)
    }
  }

  override fun pull(): RuntimeProgress {
    emit(RuntimeInstallPhase.DOWNLOADING, 0, "resolving latest release")
    return try {
      localPackage()?.let { local ->
        emit(RuntimeInstallPhase.INSTALLING, 40, "using local package ${local.name}")
        installer.installFromFile(local)
        val version = installer.installedVersion()
        enableAndStart()
        emit(RuntimeInstallPhase.DONE, 100, "installed from local package", version)
        return state.get()
      }
      if (!runtimeProperties.wantsOnline()) throw SuwayomiException("Online source is disabled by LANTAI_RUNTIME_SOURCE_MODE")
      installer.downloadLatest { percent, message ->
        emit(RuntimeInstallPhase.DOWNLOADING, (percent * 0.6).toInt(), message)
      }
      val version = installer.installedVersion()
      enableAndStart()
      emit(RuntimeInstallPhase.DONE, 100, "installed", version)
      state.get()
    } catch (e: Exception) {
      emit(RuntimeInstallPhase.FAILED, 0, e.message)
      throw if (e is SuwayomiException) e else SuwayomiException(e.message ?: "install failed", e)
    }
  }

  override fun uninstall(purgeData: Boolean): RuntimeUninstallResult {
    processManager.stop()
    val jar = configWriter.jarPath()
    val removed = runCatching { Files.deleteIfExists(jar) }.getOrDefault(false)
    var dataRemoved = false
    if (purgeData) {
      val data = configWriter.dataDir()
      if (data.exists()) {
        runCatching {
          Files.walk(data).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
          }
        }.onSuccess { dataRemoved = true }
          .onFailure { logger.warn(it) { "Unable to remove Suwayomi data at $data" } }
      }
    }
    settingsProvider.suwayomiEnabled = false
    emit(RuntimeInstallPhase.IDLE, 0, if (purgeData) "uninstalled with data" else "uninstalled")
    return RuntimeUninstallResult(
      kind = kind,
      removed = removed,
      dataRemoved = dataRemoved,
      message = if (purgeData) "Suwayomi and its data were removed" else "Suwayomi was removed, data kept",
    )
  }

  override fun steps(): List<RuntimeStep> {
    val home = configWriter.home()
    val jar = configWriter.jarPath()
    val data = configWriter.dataDir()
    return listOf(
      RuntimeStep("1. 环境检测", "java -version\n[ -w \"$home\" ] || echo \"config volume is not writable\""),
      RuntimeStep("2. 准备目录", "mkdir -p \"${jar.parent}\""),
      RuntimeStep(
        "3. 拉取安装包",
        "# 实际下载地址见 GET api/lantai/v1/runtimes/suwayomi/remote\n" +
          "curl -fL \"\$SUWAYOMI_ASSET_URL\" -o \"$jar.download\"",
      ),
      RuntimeStep("4. 校验", "head -c 2 \"$jar.download\" | od -An -tx1 | grep -q '50 4b'"),
      RuntimeStep("5. 落盘", "mv \"$jar.download\" \"$jar\""),
      RuntimeStep(
        "6. 启动",
        "java -Xmx1g -Dsuwayomi.tachidesk.config.server.rootDir=\"$data\" -jar \"$jar\"",
        optional = true,
      ),
    )
  }

  override fun progress(): RuntimeProgress = state.get()

  override fun isEnabled(): Boolean = settingsProvider.suwayomiEnabled ?: onlineProperties.enabled

  override fun setEnabled(value: Boolean): RuntimeToggleResult {
    settingsProvider.suwayomiEnabled = value
    if (!value) {
      runCatching { processManager.stop() }
      return RuntimeToggleResult(kind, false, false, "Suwayomi stopped")
    }
    val started = runCatching { processManager.ensureRunning() }
    return started.fold(
      onSuccess = { RuntimeToggleResult(kind, true, it.running, null) },
      onFailure = { RuntimeToggleResult(kind, true, false, it.message ?: "Suwayomi is enabled but failed to start") },
    )
  }

  private fun enableAndStart() {
    runCatching { setEnabled(true) }
      .onFailure { logger.warn(it) { "Suwayomi installed but failed to start" } }
  }

  private fun localPackage(): Path? {
    val dir = environment.packageDir() ?: return null
    if (!Files.isDirectory(dir)) return null
    return runCatching {
      Files.list(dir).use { stream ->
        stream
          .filter { Files.isRegularFile(it) }
          .filter { it.fileName.toString().startsWith("Suwayomi-Server") && it.fileName.toString().endsWith(".jar") }
          .sorted()
          .findFirst()
          .orElse(null)
      }
    }.getOrNull()
  }

  private fun prefix(
    mirror: String,
    url: String,
  ): String = mirror.trimEnd('/') + "/" + url

  private fun emit(
    phase: RuntimeInstallPhase,
    percent: Int,
    message: String?,
    version: String? = null,
  ) {
    val now = Instant.now()
    val previous = state.get()
    state.set(
      RuntimeProgress(
        kind = kind,
        phase = phase,
        percent = percent.coerceIn(0, 100),
        message = message,
        version = version ?: previous.version,
        startedAt = previous.startedAt ?: now,
        finishedAt = if (phase == RuntimeInstallPhase.DONE || phase == RuntimeInstallPhase.FAILED) now else null,
      ),
    )
  }

  companion object {
    private const val MIN_FREE_BYTES = 2L * 1024 * 1024 * 1024
  }
}
