package org.lantai.infrastructure.runtime.install

import io.github.oshai.kotlinlogging.KotlinLogging
import org.lantai.infrastructure.calibre.CalibreException
import org.lantai.infrastructure.calibre.CalibreInstaller
import org.lantai.infrastructure.calibre.CalibreProperties
import org.lantai.infrastructure.calibre.CalibreRuntime
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
class CalibreInstallerAdapter(
  private val installer: CalibreInstaller,
  private val runtime: CalibreRuntime,
  private val environment: RuntimeEnvironment,
  private val runtimeProperties: RuntimeProperties,
  private val properties: CalibreProperties,
) : RuntimeInstaller,
  RuntimeToggle {
  override val kind: String = "calibre"

  override val displayName: String = "Calibre"

  private val state = AtomicReference(RuntimeProgress(kind = kind))

  override fun detect(): RuntimeDetection {
    val dir = runtime.installDir()
    val installed = runtime.managedExecutable() != null
    val executable = runtime.executable()
    val version = executable?.let { runCatching { runtime.probeVersion(it) }.getOrNull() }
    val free = environment.freeSpace(dir)
    val checks = mutableListOf<RuntimeCheck>()
    checks +=
      RuntimeCheck(
        name = "package",
        ok = installed,
        detail = if (installed) executable?.toString() ?: dir.toString() else "not installed",
        hint = "Install Calibre from the official mirror or upload calibre-<version>-${runtime.arch()}.txz",
      )
    checks +=
      RuntimeCheck(
        name = "directory",
        ok = environment.writable(dir),
        detail = dir.toString(),
        hint = "Directory is not writable, check the mounted volume permissions",
      )
    checks +=
      RuntimeCheck(
        name = "disk",
        ok = free >= MIN_FREE_BYTES,
        detail = environment.humanBytes(free),
        hint = "Free at least ${environment.humanBytes(MIN_FREE_BYTES)} on the config volume",
      )
    if (environment.isLinux()) {
      val musl = environment.isMusl()
      checks +=
        RuntimeCheck(
          name = "libc",
          ok = !musl,
          detail = if (musl) "musl" else "glibc",
          hint = "当前镜像基于 alpine（musl libc），官方 Calibre 是 glibc 构建，无法在容器内运行：请改用 Debian 基础镜像，或把宿主机已装好的 Calibre 目录挂进容器后在「转换设置」里指定 ebook-convert 路径",
        )
      if (!musl) {
        val python = environment.commandVersion("python3")
        checks +=
          RuntimeCheck(
            name = "python3",
            ok = python != null,
            detail = python ?: "missing",
            hint = "Image is missing python3; rebuild it with the Calibre runtime dependencies",
          )
        val missing = environment.ldconfigHas(QT_LIBS)
        checks +=
          RuntimeCheck(
            name = "qt",
            ok = missing.isEmpty(),
            detail = if (missing.isEmpty()) "all present" else missing.joinToString(", "),
            hint = "Image is missing ${missing.joinToString(", ")}; rebuild it with the Calibre runtime dependencies",
          )
      }
    }
    if (installed) {
      checks +=
        RuntimeCheck(
          name = "version",
          ok = version != null,
          detail = version ?: "ebook-convert did not answer",
          hint = "ebook-convert exists but failed to run, check the container environment",
        )
    }
    val blockers = checks.filterNot { it.ok }.map { it.hint ?: it.name }
    return RuntimeDetection(
      kind = kind,
      platform = environment.platform(),
      installed = installed,
      enabled = runtime.isEnabled(),
      ready = installed && blockers.isEmpty(),
      checks = checks,
      blockers = blockers,
    )
  }

  override fun describe(): RuntimePackageInfo {
    val release = runCatching { installer.latestRelease() }.getOrNull()
    val sources = mutableListOf<RuntimeSource>()
    release?.urls?.forEachIndexed { index, url ->
      sources += RuntimeSource(if (index == 0) "official" else "official fallback $index", url, RuntimeSourceKind.OFFICIAL)
    }
    val mirror = runtimeProperties.mirror.takeIf { it.isNotBlank() }
    if (mirror != null && release != null) {
      sources += RuntimeSource("configured mirror", prefix(mirror, release.url), RuntimeSourceKind.MIRROR)
    }
    localPackage()?.let { sources += RuntimeSource("local package", it.toString(), RuntimeSourceKind.LOCAL) }
    return RuntimePackageInfo(
      kind = kind,
      version = release?.version,
      fileName = release?.fileName ?: "calibre-${runtime.arch()}.txz",
      size = release?.size,
      sources = sources,
      notes =
        listOf(
          "Unpacked size is roughly 500 MB, make sure the config volume has room",
          "Only MOBI/AZW3/FB2/LIT/PDB conversion needs Calibre; EPUB/PDF/CBZ work without it",
        ),
    )
  }

  override fun accept(
    bytes: ByteArray,
    fileName: String?,
  ): RuntimePackage {
    val name = fileName?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "calibre-${runtime.arch()}.txz"
    val dir = environment.stagingDir(kind)
    Files.createDirectories(dir)
    val target = dir.resolve(name)
    val temp = dir.resolve("$name.download")
    Files.write(temp, bytes)
    try {
      installer.validatePackage(temp)
    } catch (e: Exception) {
      Files.deleteIfExists(temp)
      throw if (e is CalibreException) e else CalibreException(e.message ?: "invalid Calibre package", e)
    }
    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
    logger.info { "Accepted Calibre package $target (${bytes.size} bytes)" }
    return RuntimePackage(
      info = RuntimePackageInfo(kind = kind, version = versionFromName(name), fileName = name, size = bytes.size.toLong()),
      file = target,
      source = RuntimeSourceKind.UPLOAD,
    )
  }

  override fun install(installed: RuntimePackage): RuntimeProgress {
    emit(RuntimeInstallPhase.INSTALLING, 40, "installing ${installed.file.name}")
    return try {
      installer.install(installed.file) { percent, message ->
        emit(RuntimeInstallPhase.INSTALLING, (40 + percent * 0.55).toInt(), message)
      }
      val status = runtime.status()
      emit(RuntimeInstallPhase.DONE, 100, "installed", status.version)
      runCatching { Files.deleteIfExists(installed.file) }
        .onSuccess { logger.info { "Removed staged Calibre package ${installed.file}" } }
      state.get()
    } catch (e: Exception) {
      emit(RuntimeInstallPhase.FAILED, 0, e.message)
      throw if (e is CalibreException) e else CalibreException(e.message ?: "install failed", e)
    }
  }

  override fun pull(): RuntimeProgress {
    emit(RuntimeInstallPhase.DOWNLOADING, 0, "resolving latest release")
    return try {
      localPackage()?.let { local ->
        emit(RuntimeInstallPhase.INSTALLING, 40, "using local package ${local.name}")
        installer.install(local) { percent, message ->
          emit(RuntimeInstallPhase.INSTALLING, (40 + percent * 0.55).toInt(), message)
        }
        val status = runtime.status()
        emit(RuntimeInstallPhase.DONE, 100, "installed from local package", status.version)
        return state.get()
      }
      if (!runtimeProperties.wantsOnline()) throw CalibreException("Online source is disabled by LANTAI_RUNTIME_SOURCE_MODE")
      val archive =
        installer.downloadInstaller { percent, message ->
          emit(RuntimeInstallPhase.DOWNLOADING, (percent * 0.4).toInt(), message)
        }
      emit(RuntimeInstallPhase.VERIFYING, 42, "package verified")
      installer.install(archive) { percent, message ->
        emit(RuntimeInstallPhase.INSTALLING, (42 + percent * 0.53).toInt(), message)
      }
      val status = runtime.status()
      emit(RuntimeInstallPhase.DONE, 100, "installed", status.version)
      runCatching { Files.deleteIfExists(archive) }
      state.get()
    } catch (e: Exception) {
      emit(RuntimeInstallPhase.FAILED, 0, e.message)
      throw if (e is CalibreException) e else CalibreException(e.message ?: "install failed", e)
    }
  }

  override fun uninstall(purgeData: Boolean): RuntimeUninstallResult {
    if (properties.installDir.isNotBlank()) {
      runtime.setEnabled(false)
      return RuntimeUninstallResult(
        kind = kind,
        removed = false,
        dataRemoved = false,
        message = "Calibre was configured at an external path; only the Lantai flag was cleared",
      )
    }
    val dir = runtime.installDir()
    var removed = false
    if (dir.exists()) {
      runCatching {
        Files.walk(dir).use { stream ->
          stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
      }.onSuccess { removed = true }
        .onFailure { logger.warn(it) { "Unable to remove Calibre at $dir" } }
    }
    runtime.setEnabled(false)
    emit(RuntimeInstallPhase.IDLE, 0, "uninstalled")
    return RuntimeUninstallResult(
      kind = kind,
      removed = removed,
      dataRemoved = false,
      message = "Calibre was removed from $dir",
    )
  }

  override fun steps(): List<RuntimeStep> {
    val dir = runtime.installDir()
    val exe = dir.resolve("bin").resolve("ebook-convert")
    val arch = runtime.arch()
    return listOf(
      RuntimeStep(
        "1. 环境检测",
        "python3 --version\n" +
          "ldconfig -p | grep -E 'libQt6Core|libxcb-cursor|libnss3|libqpdf' || echo \"missing runtime libraries\"",
      ),
      RuntimeStep("2. 准备目录", "mkdir -p \"$dir\""),
      RuntimeStep(
        "3. 拉取安装包",
        "# 实际地址见 GET api/lantai/v1/runtimes/calibre/remote\n" +
          "curl -fL \"\$CALIBRE_PACKAGE_URL\" -o /tmp/calibre-$arch.txz",
      ),
      RuntimeStep("4. 校验", "head -c 2 /tmp/calibre-$arch.txz | od -An -tx1 | grep -q 'fd 37'"),
      RuntimeStep("5. 解压", "tar -xJf /tmp/calibre-$arch.txz -C \"$dir\""),
      RuntimeStep("6. 探测", "QT_QPA_PLATFORM=offscreen \"$exe\" --version"),
    )
  }

  override fun progress(): RuntimeProgress = state.get()

  override fun isEnabled(): Boolean = runtime.isEnabled()

  override fun setEnabled(value: Boolean): RuntimeToggleResult {
    runtime.setEnabled(value)
    val status = runtime.status()
    val message =
      when {
        !value -> "Calibre integration disabled"
        status.available -> null
        else -> status.message ?: "Calibre is enabled but ebook-convert is unavailable"
      }
    return RuntimeToggleResult(kind, value, status.available, message)
  }

  private fun localPackage(): Path? {
    val dir = environment.packageDir() ?: return null
    if (!Files.isDirectory(dir)) return null
    return runCatching {
      Files.list(dir).use { stream ->
        stream
          .filter { Files.isRegularFile(it) }
          .filter { it.fileName.toString().startsWith("calibre-") }
          .filter {
            val n = it.fileName.toString().lowercase()
            n.endsWith(".txz") || n.endsWith(".tar.xz") || n.endsWith(".zip") || n.endsWith(".exe")
          }.sorted()
          .findFirst()
          .orElse(null)
      }
    }.getOrNull()
  }

  private fun versionFromName(name: String): String? = Regex("""\d+\.\d+(?:\.\d+)?""").find(name)?.value

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
    private val QT_LIBS = listOf("libQt6Core.so", "libxcb-cursor.so", "libnss3.so", "libqpdf.so")
  }
}
