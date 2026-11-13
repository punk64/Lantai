package org.lantai.infrastructure.plugin

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.f4b6a3.tsid.TsidCreator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.lantai.domain.model.PluginCapabilities
import org.lantai.domain.model.PluginGrant
import org.lantai.domain.model.PluginInstance
import org.lantai.domain.model.PluginMode
import org.lantai.domain.model.PluginState
import org.lantai.domain.persistence.PluginStore
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.SecureRandom
import java.time.LocalDateTime
import java.util.Base64

private val logger = KotlinLogging.logger {}

@Service
class PluginInstanceService(
  private val store: PluginStore,
  private val httpClient: PluginHttpClient,
  private val processManager: PluginProcessManager,
  private val installer: PluginPackageInstaller,
  private val paths: PluginPaths,
  private val tokenService: PluginTokenService,
  private val repositoryClient: PluginRepositoryClient,
) {
  private val mapper = ObjectMapper()

  fun list(): List<PluginInstance> = store.findAllInstances()

  fun pluginIdOf(instanceId: String): String = requireInstance(instanceId).pluginId

  fun grants(instanceId: String): List<PluginGrant> = store.findGrantsByPluginId(requireInstance(instanceId).pluginId)

  fun install(
    zipPath: String,
    ownerId: String,
    listener: PluginInstallListener = PluginInstallListener.NONE,
  ): PluginInstance {
    listener.on(PHASE_EXTRACT, -1, "正在解压插件包")
    val pkg = installer.install(zipPath) { pct, label -> listener.on(PHASE_EXTRACT, pct, label) }
    if (store.findByPluginIdOrNull(pkg.pluginId) != null) {
      purgeHome(pkg.homeDir)
      throw PluginConflictException("插件 ${pkg.pluginId} 已存在")
    }
    return runCatching {
      listener.on(PHASE_REGISTER, -1, "正在创建插件实例")
      val port = processManager.allocatePort()
      val token = tokenService.issue(pkg.pluginId, ownerId.takeIf { it.isNotBlank() } ?: PluginTokenService.SYSTEM_OWNER)
      val instance =
        PluginInstance(
          pluginId = pkg.pluginId,
          name = pkg.name,
          version = pkg.version,
          apiVersion = pkg.protocol,
          mode = PluginMode.MANAGED,
          runtimeKind = pkg.runtimeKind,
          entry = pkg.entry,
          homeDir = pkg.homeDir,
          endpoint = "http://127.0.0.1:$port",
          port = port,
          hostKey = generateHostKey(),
          tokenId = token.id,
          state = PluginState.PENDING,
          capabilities = encodeCapabilities(pkg.capabilities),
          memoryMb = pkg.memoryMb,
        )
      store.insertInstance(instance)
      val autoGranted = pkg.capabilities.filter { it != PANEL_CAPABILITY }
      if (autoGranted.isNotEmpty()) {
        store.replaceGrants(
          pkg.pluginId,
          autoGranted.mapIndexed { index, capability ->
            PluginGrant(pluginId = pkg.pluginId, capability = capability, isDefault = index == 0, grantedBy = ownerId)
          },
        )
        tokenService.syncScopes(instance, autoGranted)
      }
      launch(instance, listener)
    }.getOrElse { ex ->
      val own = store.findByPluginIdOrNull(pkg.pluginId)
      if (own != null && own.homeDir == pkg.homeDir) {
        tokenService.revokeByPluginId(pkg.pluginId)
        store.deleteInstance(own.id)
      }
      purgeHome(pkg.homeDir)
      throw ex
    }
  }

  fun installFromIndex(
    entry: PluginIndexEntry,
    ownerId: String,
    listener: PluginInstallListener = PluginInstallListener.NONE,
  ): PluginInstance {
    listener.on(PHASE_DOWNLOAD, -1, "正在下载插件包")
    val zip = repositoryClient.download(entry) { pct, label -> listener.on(PHASE_DOWNLOAD, pct, label) }
    try {
      val instance = install(zip.toString(), ownerId, listener)
      if (instance.pluginId != entry.pluginId) {
        delete(instance.id)
        throw PluginRequestException("插件包声明的 id 与索引不一致：${instance.pluginId} ≠ ${entry.pluginId}")
      }
      return instance
    } finally {
      runCatching { Files.deleteIfExists(zip) }
    }
  }

  fun upgrade(
    instanceId: String,
    zipPath: String,
    listener: PluginInstallListener = PluginInstallListener.NONE,
  ): PluginInstance {
    val stored = requireInstance(instanceId)
    if (stored.mode != PluginMode.MANAGED) throw PluginRequestException("外部插件不支持就地升级，请用新版本重新注册")
    listener.on(PHASE_EXTRACT, -1, "正在解压插件包")
    val pkg = installer.install(zipPath) { pct, label -> listener.on(PHASE_EXTRACT, pct, label) }
    if (pkg.pluginId != stored.pluginId) {
      purgeHome(pkg.homeDir)
      throw PluginRequestException("插件包声明的 id 与已安装实例不一致：${pkg.pluginId} ≠ ${stored.pluginId}")
    }
    if (pkg.version == stored.version) {
      purgeHome(pkg.homeDir)
      throw PluginConflictException("插件已是 ${pkg.version}，无需升级")
    }
    return switchVersion(stored, pkg, listener)
  }

  fun upgradeFromIndex(
    instanceId: String,
    entry: PluginIndexEntry,
    listener: PluginInstallListener = PluginInstallListener.NONE,
  ): PluginInstance {
    val stored = requireInstance(instanceId)
    if (entry.pluginId != stored.pluginId) throw PluginRequestException("索引条目的 id 与实例不一致：${entry.pluginId} ≠ ${stored.pluginId}")
    listener.on(PHASE_DOWNLOAD, -1, "正在下载插件包")
    val zip = repositoryClient.download(entry) { pct, label -> listener.on(PHASE_DOWNLOAD, pct, label) }
    try {
      return upgrade(instanceId, zip.toString(), listener)
    } finally {
      runCatching { Files.deleteIfExists(zip) }
    }
  }

  fun logTail(
    instanceId: String,
    lines: Int,
  ): List<String> {
    val stored = requireInstance(instanceId)
    if (stored.mode != PluginMode.MANAGED) return emptyList()
    val home = runCatching { Paths.get(stored.homeDir).toAbsolutePath().normalize() }.getOrNull() ?: return emptyList()
    if (!home.startsWith(paths.root())) return emptyList()
    val log = home.resolve("logs").resolve("plugin.log")
    if (!Files.isRegularFile(log)) return emptyList()
    val limit = lines.coerceIn(1, MAX_LOG_LINES)
    return runCatching { Files.readAllLines(log).takeLast(limit) }.getOrDefault(emptyList())
  }

  fun register(
    endpoint: String,
    hostKey: String?,
    ownerId: String,
  ): PluginInstance {
    val normalized = endpoint.trim()
    if (normalized.isEmpty()) throw PluginRequestException("插件地址不能为空")
    val providedKey = hostKey?.trim()?.takeIf { it.isNotEmpty() }
    val handshake = httpClient.handshake(normalized, providedKey)
    val manifest = handshake.manifest
    val violations = manifestIssues(manifest?.raw)
    if (violations.isNotEmpty()) throw PluginRequestException(PluginContract.describe(violations))
    if (manifest != null && store.findByPluginIdOrNull(manifest.pluginId) != null) {
      throw PluginConflictException("插件 ${manifest.pluginId} 已存在")
    }
    val id = TsidCreator.getTsid256().toString()
    val pluginId = manifest?.pluginId ?: "pending-${id.lowercase()}"
    val token = manifest?.let { tokenService.issue(pluginId, ownerId.takeIf { it.isNotBlank() } ?: PluginTokenService.SYSTEM_OWNER) }
    val instance =
      PluginInstance(
        pluginId = pluginId,
        name = manifest?.name ?: normalized,
        version = manifest?.version ?: "",
        apiVersion = manifest?.apiVersion ?: 0,
        mode = PluginMode.EXTERNAL,
        runtimeKind = manifest?.runtimeKind ?: "",
        entry = manifest?.entry ?: "",
        homeDir = "",
        endpoint = normalized,
        hostKey = providedKey ?: generateHostKey(),
        tokenId = token?.id,
        state = handshake.state,
        statusMessage = handshake.message,
        capabilities = encodeCapabilities(manifest?.capabilities ?: emptyList()),
        manifest = manifest?.raw ?: "{}",
        lastHealthDate = LocalDateTime.now().takeIf { handshake.state == PluginState.READY },
        id = id,
      )
    return runCatching {
      store.insertInstance(instance)
      instance
    }.getOrElse { ex ->
      if (token != null) tokenService.revokeByPluginId(pluginId)
      throw ex
    }
  }

  fun refresh(instanceId: String): PluginInstance {
    val stored = requireInstance(instanceId)
    if (!stored.enabled) return persist(stored.copy(state = PluginState.DISABLED, statusMessage = "已停用", pid = null))
    if (stored.mode == PluginMode.MANAGED) {
      processManager.stop(stored.id)
      return launch(stored.copy(pid = null))
    }
    return refreshExternal(stored)
  }

  fun prepareById(instanceId: String): PluginInstance = prepare(requireInstance(instanceId).pluginId)

  fun prepare(pluginId: String): PluginInstance {    val stored = store.findByPluginIdOrNull(pluginId) ?: throw PluginNotFoundException("插件 $pluginId 不存在")
    if (!stored.enabled) throw PluginRequestException("插件 $pluginId 已停用")
    if (stored.state == PluginState.READY) return persist(stored.copy(lastUsedDate = LocalDateTime.now()))
    val resumed =
      if (stored.mode == PluginMode.MANAGED) launch(stored.copy(pid = null))
      else refreshExternal(stored)
    if (resumed.state != PluginState.READY) {
      throw PluginRequestException("插件 $pluginId 未就绪：${resumed.statusMessage ?: resumed.state}")
    }
    return resumed
  }

  @EventListener(ApplicationReadyEvent::class)
  fun resumeManaged() {
    val candidates = store.findAllInstances().filter { it.mode == PluginMode.MANAGED && it.enabled }
    if (candidates.isEmpty()) return
    Thread(
      {
        for (item in candidates) {
          runCatching {
            val alive = item.pid?.let { pid -> ProcessHandle.of(pid.toLong()).map { it.isAlive }.orElse(false) } ?: false
            if (alive) return@runCatching
            store.updateInstance(item.copy(state = PluginState.PENDING, pid = null, statusMessage = null))
            prepare(item.pluginId)
          }
        }
      },
      "plugin-resume",
    ).apply { isDaemon = true }.start()
  }

  private fun refreshExternal(stored: PluginInstance): PluginInstance {
    val endpoint = stored.endpoint?.takeIf { it.isNotBlank() } ?: throw PluginRequestException("插件地址为空，无法握手")
    val handshake = httpClient.handshake(endpoint, stored.hostKey)
    val manifest = handshake.manifest
    val effectiveState = if (stored.enabled) handshake.state else PluginState.DISABLED
    val updated =
      stored.copy(
        name = manifest?.name ?: stored.name,
        version = manifest?.version ?: stored.version,
        apiVersion = manifest?.apiVersion ?: stored.apiVersion,
        runtimeKind = manifest?.runtimeKind ?: stored.runtimeKind,
        entry = manifest?.entry ?: stored.entry,
        tokenId = manifest?.let { ensureTokenId(stored.pluginId, stored.tokenId) } ?: stored.tokenId,
        state = effectiveState,
        statusMessage = handshake.message,
        capabilities = if (manifest != null) encodeCapabilities(manifest.capabilities) else stored.capabilities,
        manifest = manifest?.raw ?: stored.manifest,
        lastHealthDate = LocalDateTime.now().takeIf { effectiveState == PluginState.READY } ?: stored.lastHealthDate,
        lastModifiedDate = LocalDateTime.now(),
      )
    store.updateInstance(updated)
    return updated
  }

  private fun launch(
    instance: PluginInstance,
    listener: PluginInstallListener = PluginInstallListener.NONE,
  ): PluginInstance {
    migrateDataDir(instance)
    listener.on(PHASE_START, -1, "正在启动插件进程")
    val started =
      runCatching {
        processManager.start(instance) { seconds ->
          listener.on(PHASE_START, -1, "正在启动插件进程（已等待 ${seconds}s）")
        }
      }.getOrElse {
        val failed =
          instance.copy(
            state = PluginState.UNAVAILABLE,
            statusMessage = it.message ?: "插件进程启动失败",
            pid = null,
            lastModifiedDate = LocalDateTime.now(),
          )
        store.updateInstance(failed)
        return failed
      }
    val endpoint = started.endpoint?.takeIf { it.isNotBlank() } ?: return persist(started.copy(state = PluginState.UNAVAILABLE, statusMessage = "插件地址为空"))
    listener.on(PHASE_HANDSHAKE, -1, "正在握手，等待插件就绪")
    val handshake = httpClient.handshake(endpoint, started.hostKey)
    val manifest = handshake.manifest
    val violations = manifestIssues(manifest?.raw)
    if (violations.isNotEmpty()) {
      processManager.stop(started.id)
      store.updateInstance(
        started.copy(
          state = PluginState.UNAVAILABLE,
          statusMessage = PluginContract.describe(violations),
          pid = null,
          lastModifiedDate = LocalDateTime.now(),
        ),
      )
      throw PluginRequestException(PluginContract.describe(violations))
    }
    val ready = handshake.state == PluginState.READY
    if (!ready) processManager.stop(started.id)
    return persist(
      started.copy(
        name = manifest?.name ?: started.name,
        version = manifest?.version ?: started.version,
        apiVersion = manifest?.apiVersion ?: started.apiVersion,
        runtimeKind = manifest?.runtimeKind?.takeIf { it.isNotBlank() } ?: started.runtimeKind,
        entry = manifest?.entry ?: started.entry,
        state = handshake.state,
        statusMessage = handshake.message,
        capabilities = if (manifest != null) encodeCapabilities(manifest.capabilities) else started.capabilities,
        manifest = manifest?.raw ?: started.manifest,
        pid = started.pid.takeIf { ready },
        lastHealthDate = LocalDateTime.now().takeIf { ready } ?: started.lastHealthDate,
        lastUsedDate = LocalDateTime.now().takeIf { ready } ?: started.lastUsedDate,
      ),
    )
  }

  private fun persist(instance: PluginInstance): PluginInstance {
    val updated = instance.copy(lastModifiedDate = LocalDateTime.now())
    store.updateInstance(updated)
    return updated
  }

  fun release(instanceId: String): PluginInstance {
    val stored = requireInstance(instanceId)
    if (!stored.enabled) throw PluginRequestException("插件已停用，无需回收")
    if (stored.mode != PluginMode.MANAGED) throw PluginRequestException("外部插件不受宿主管理，无法回收")
    if (stored.state == PluginState.PENDING) throw PluginRequestException("插件正在启动，无法回收")
    processManager.stop(stored.id)
    return persist(
      stored.copy(
        state = PluginState.IDLE,
        statusMessage = "已手动释放内存，下次访问自动启动",
        pid = null,
      ),
    )
  }

  fun setEnabled(
    instanceId: String,
    enabled: Boolean,
  ): PluginInstance {
    val stored = requireInstance(instanceId)
    if (!enabled) {
      processManager.stop(stored.id)
      return persist(
        stored.copy(
          enabled = false,
          state = PluginState.DISABLED,
          statusMessage = "已停用",
          pid = null,
        ),
      )
    }
    store.updateInstance(stored.copy(enabled = true, lastModifiedDate = LocalDateTime.now()))
    return refresh(instanceId)
  }

  fun updateGrants(
    instanceId: String,
    capabilities: List<String>,
    defaultCapability: String?,
    grantedBy: String?,
  ): List<PluginGrant> {
    val instance = requireInstance(instanceId)
    val declared = decodeCapabilities(instance.capabilities)
    val requested = capabilities.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    requested.forEach { capability ->
      if (!PluginCapabilities.isKnown(capability)) throw PluginRequestException("未知能力：$capability")
      if (capability !in declared) throw PluginRequestException("插件未声明能力：$capability")
    }
    val default = defaultCapability?.trim()?.takeIf { it.isNotEmpty() }
    if (default != null && default !in requested) throw PluginRequestException("默认实现必须包含在授权列表内")
    store.replaceGrants(
      instance.pluginId,
      requested.map { capability ->
        PluginGrant(pluginId = instance.pluginId, capability = capability, isDefault = capability == default, grantedBy = grantedBy)
      },
    )
    tokenService.syncScopes(instance, requested)
    return store.findGrantsByPluginId(instance.pluginId)
  }

  fun updateSettings(
    instanceId: String,
    raw: String,
  ): PluginInstance {
    val stored = requireInstance(instanceId)
    val node = runCatching { mapper.readTree(raw) }.getOrElse { throw PluginRequestException("配置不是合法 JSON") }
    if (!node.isObject) throw PluginRequestException("配置必须是 JSON 对象")
    val normalized = mapper.writeValueAsString(node)
    if (normalized.toByteArray(Charsets.UTF_8).size > MAX_SETTINGS_BYTES) {
      throw PluginRequestException("配置不得超过 $MAX_SETTINGS_BYTES 字节")
    }
    store.updateInstance(stored.copy(settings = normalized, lastModifiedDate = LocalDateTime.now()))
    if (stored.mode == PluginMode.MANAGED && stored.enabled) return refresh(instanceId)
    return requireInstance(instanceId)
  }

  fun delete(instanceId: String) {
    val stored = requireInstance(instanceId)
    processManager.stop(instanceId)
    tokenService.revoke(stored)
    store.deleteInstance(instanceId)
    if (stored.mode == PluginMode.MANAGED) purgeHome(stored.homeDir, purgeData = true)
  }

  private fun switchVersion(
    stored: PluginInstance,
    pkg: InstalledPackage,
    listener: PluginInstallListener = PluginInstallListener.NONE,
  ): PluginInstance {
    processManager.stop(stored.id)
    store.updateInstance(
      stored.copy(
        homeDir = pkg.homeDir,
        version = pkg.version,
        apiVersion = pkg.protocol,
        runtimeKind = pkg.runtimeKind,
        entry = pkg.entry,
        memoryMb = pkg.memoryMb,
        capabilities = encodeCapabilities(pkg.capabilities),
        state = PluginState.PENDING,
        statusMessage = null,
        pid = null,
        lastModifiedDate = LocalDateTime.now(),
      ),
    )
    val upgraded =
      runCatching { launch(requireInstance(stored.id), listener) }.getOrElse { ex ->
        val reason = ex.message ?: ex.javaClass.simpleName
        processManager.stop(stored.id)
        store.updateInstance(
          stored.copy(state = PluginState.PENDING, statusMessage = null, pid = null, lastModifiedDate = LocalDateTime.now()),
        )
        val restored = runCatching { launch(requireInstance(stored.id)) }.getOrNull()
        removeVersionDir(pkg.homeDir)
        throw PluginRequestException(
          "新版本 ${pkg.version} 不符合插件开发约定：$reason；" +
            if (restored?.state == PluginState.READY) {
              "已回滚到 ${stored.version}"
            } else {
              "回滚后旧版本仍未就绪：${restored?.statusMessage ?: restored?.state ?: "启动异常"}"
            },
        )
      }
    if (upgraded.state == PluginState.READY) {
      syncGrantsAfterUpgrade(upgraded, pkg.capabilities)
      if (stored.homeDir != pkg.homeDir) removeVersionDir(stored.homeDir)
      return upgraded
    }
    val failure = upgraded.statusMessage ?: upgraded.state.name
    processManager.stop(stored.id)
    store.updateInstance(stored.copy(lastModifiedDate = LocalDateTime.now()))
    val restored = launch(stored)
    removeVersionDir(pkg.homeDir)
    throw PluginRequestException(
      "新版本 ${pkg.version} 启动失败：$failure；" +
        if (restored.state == PluginState.READY) "已回滚到 ${stored.version}" else "回滚后旧版本仍未就绪：${restored.statusMessage ?: restored.state}",
    )
  }

  private fun syncGrantsAfterUpgrade(
    instance: PluginInstance,
    declared: List<String>,
  ) {
    val current = store.findGrantsByPluginId(instance.pluginId)
    val kept = current.filter { it.capability in declared }
    val missing = declared.filter { capability -> capability != PANEL_CAPABILITY && kept.none { it.capability == capability } }
    if (missing.isEmpty() && kept.size == current.size) return
    val added =
      missing.mapIndexed { index, capability ->
        PluginGrant(
          pluginId = instance.pluginId,
          capability = capability,
          isDefault = kept.isEmpty() && index == 0,
          grantedBy = null,
        )
      }
    val merged = kept + added
    val normalized =
      if (merged.count { it.isDefault } == 1) {
        merged
      } else {
        merged.mapIndexed { index, grant -> grant.copy(isDefault = index == 0) }
      }
    store.replaceGrants(instance.pluginId, normalized)
    tokenService.syncScopes(instance, normalized.map { it.capability })
  }

  private fun removeVersionDir(homeDir: String) {
    val path = runCatching { Paths.get(homeDir).toAbsolutePath().normalize() }.getOrNull() ?: return
    if (path == paths.root() || !path.startsWith(paths.root())) return
    val parent = path.parent
    if (parent == null || parent == paths.root() || !parent.startsWith(paths.root())) return
    deleteTree(path)
  }

  private fun migrateDataDir(instance: PluginInstance) {
    val home = runCatching { Paths.get(instance.homeDir).toAbsolutePath().normalize() }.getOrNull() ?: return
    val parent = home.parent ?: return
    val legacy = home.resolve("data")
    val target = parent.resolve("data")
    if (Files.isDirectory(legacy) && !Files.exists(target)) {
      runCatching { Files.move(legacy, target) }
    }
  }

  private fun purgeHome(
    homeDir: String,
    purgeData: Boolean = false,
  ) {
    val path = runCatching { Paths.get(homeDir).toAbsolutePath().normalize() }.getOrNull() ?: return
    if (path == paths.root() || !path.startsWith(paths.root())) return
    deleteTree(path)
    val parent = path.parent
    if (purgeData && parent != null && parent != paths.root() && parent.startsWith(paths.root())) {
      deleteTree(parent.resolve("data"))
      runCatching { Files.deleteIfExists(parent) }
    }
  }

  private fun deleteTree(path: Path) {
    repeat(4) { attempt ->
      runCatching {
        Files.walk(path).use { walk ->
          walk.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
      }
      if (!Files.exists(path)) return
      if (attempt < 3) Thread.sleep(250L * (attempt + 1))
    }
    logger.warn { "Failed to remove plugin path $path" }
  }

  private fun requireInstance(instanceId: String): PluginInstance =
    store.findInstanceByIdOrNull(instanceId) ?: throw PluginNotFoundException("插件实例不存在")

  private fun manifestIssues(raw: String?): List<PluginContractIssue> {
    if (raw.isNullOrBlank()) return emptyList()
    val node =
      runCatching { mapper.readTree(raw) }.getOrNull()
        ?: return listOf(PluginContractIssue("manifest.json", "运行时清单不是合法 JSON"))
    return PluginContract.validate(node)
  }

  private fun encodeCapabilities(capabilities: List<String>): String = mapper.writeValueAsString(capabilities)

  private fun decodeCapabilities(stored: String): List<String> =
    runCatching { mapper.readTree(stored).map { it.asText() } }.getOrDefault(emptyList())

  private fun ensureTokenId(
    pluginId: String,
    existing: String?,
  ): String? {
    if (existing != null) return existing
    return runCatching { tokenService.issue(pluginId, PluginTokenService.SYSTEM_OWNER).id }.getOrNull()
  }

  private fun generateHostKey(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
  }

  private companion object {
    private const val MAX_LOG_LINES = 1000
    private const val MAX_SETTINGS_BYTES = 16 * 1024
    private const val PANEL_CAPABILITY = "ui.panel"
  }
}

class PluginNotFoundException(message: String) : RuntimeException(message)

class PluginConflictException(message: String) : RuntimeException(message)

class PluginRequestException(message: String) : RuntimeException(message)
