package org.lantai.infrastructure.online

import io.github.oshai.kotlinlogging.KotlinLogging
import org.lantai.infrastructure.log.ExtensionLog
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

private const val EXTENSION_PACKAGE_LIMIT_BYTES = 64L * 1024 * 1024
private const val DEFAULT_EXTENSION_PACKAGE_NAME = "extension.jar"
private const val LEGACY_EXTENSION_STORE_PREFIX = "https://github.com/keiyoushi/extensions/raw"

data class SuwayomiStatusDto(
  val running: Boolean,
  val managed: Boolean,
  val version: String?,
  val supported: Boolean,
  val port: Int,
  val endpoint: String,
  val jarPresent: Boolean,
  val jarPath: String,
  val home: String,
  val dataDir: String,
  val configPath: String,
  val message: String?,
  val installedCount: Int,
  val updatableCount: Int,
  val minVersion: String,
  val maxVersion: String,
)

data class SuwayomiExtensionDto(
  val pkgName: String,
  val name: String?,
  val versionName: String?,
  val lang: String?,
  val isNsfw: Boolean,
  val hasUpdate: Boolean,
  val isObsolete: Boolean,
  val iconUrl: String?,
)

data class SuwayomiCatalogDto(
  val pkgName: String,
  val name: String,
  val versionName: String,
  val lang: String,
  val iconUrl: String?,
  val installed: Boolean,
  val installedVersion: String?,
  val hasUpdate: Boolean,
)

data class SuwayomiCatalogPage(
  val total: Int,
  val items: List<SuwayomiCatalogDto>,
  val languages: List<SuwayomiCatalogLang> = emptyList(),
)

data class SuwayomiCatalogLang(
  val code: String,
  val count: Int,
)

data class SuwayomiExtensionSourceDto(
  val id: String,
  val name: String,
  val lang: String?,
  val isConfigurable: Boolean,
)

data class SuwayomiReleaseDto(
  val tag: String,
  val publishedAt: String?,
  val assetName: String?,
  val size: Long,
  val installedVersion: String?,
)

data class SuwayomiActionResult(
  val success: Boolean,
  val message: String?,
)

data class SuwayomiRouteDto(
  val id: String,
  val name: String,
)

@Service
class SuwayomiAdminService(
  private val processManager: SuwayomiProcessManager,
  private val installer: SuwayomiInstaller,
  private val configWriter: SuwayomiConfigWriter,
  private val client: SuwayomiClient,
  private val properties: OnlineProperties,
  private val extLog: ExtensionLog,
) {
  fun extensionIcon(pkgName: String): org.springframework.http.ResponseEntity<ByteArray>? = client.extensionIcon(pkgName)

  fun status(): SuwayomiStatusDto {
    val runtime = processManager.status()
    val installed = if (runtime.running) runCatching { client.listExtensions() }.getOrDefault(emptyList()).filter { it.isInstalled } else emptyList()
    return SuwayomiStatusDto(
      running = runtime.running,
      managed = runtime.managed,
      version = runtime.version,
      supported = installer.isSupported(runtime.version),
      port = runtime.port,
      endpoint = runtime.endpoint,
      jarPresent = runtime.jarPresent,
      jarPath = runtime.jarPath,
      home = runtime.home,
      dataDir = runtime.dataDir,
      configPath = runtime.configPath,
      message = runtime.message,
      installedCount = installed.size,
      updatableCount = installed.count { it.hasUpdate },
      minVersion = properties.suwayomiMinVersion,
      maxVersion = properties.suwayomiMaxVersion,
    )
  }

  fun settings(): Map<String, Any?> {
    val node = client.settings()
    return linkedMapOf(
      "ip" to node.path("ip").asText(null),
      "port" to node.path("port").asInt(0),
      "authMode" to node.path("authMode").asText(null),
      "downloadAsCbz" to node.path("downloadAsCbz").asBoolean(false),
      "downloadsPath" to node.path("downloadsPath").asText(""),
      "autoDownloadNewChapters" to node.path("autoDownloadNewChapters").asBoolean(false),
      "excludeEntryWithUnreadChapters" to node.path("excludeEntryWithUnreadChapters").asBoolean(true),
      "autoDownloadNewChaptersLimit" to node.path("autoDownloadNewChaptersLimit").asInt(0),
      "maxSourcesInParallel" to node.path("maxSourcesInParallel").asInt(6),
      "globalUpdateInterval" to node.path("globalUpdateInterval").asDouble(12.0),
      "updateMangas" to node.path("updateMangas").asBoolean(false),
      "backupInterval" to node.path("backupInterval").asInt(0),
      "backupPath" to node.path("backupPath").asText(""),
    )
  }

  fun updateSettings(values: Map<String, Any>): Map<String, Any?> {
    client.setSettings(values)
    return settings()
  }

  fun listExtensions(): List<SuwayomiExtensionDto> =
    client.listExtensions().filter { it.isInstalled }.map {
      SuwayomiExtensionDto(
        pkgName = it.pkgName,
        name = it.name,
        versionName = it.versionName,
        lang = it.lang,
        isNsfw = it.isNsfw,
        hasUpdate = it.hasUpdate,
        isObsolete = it.isObsolete,
        iconUrl = it.iconUrl,
      )
    }

  fun extensionSources(pkgName: String): List<SuwayomiExtensionSourceDto> =
    client.extensionSources(pkgName).map { SuwayomiExtensionSourceDto(it.id, it.name, it.lang, it.isConfigurable) }

  fun catalog(
    keyword: String?,
    lang: String?,
    onlyInstalled: Boolean,
    offset: Int,
    limit: Int,
    force: Boolean,
    store: String?,
  ): SuwayomiCatalogPage {
    if (force) {
      runCatching { client.fetchExtensions() }
        .onFailure { extLog.warn("刷新扩展仓库清单失败：${it.javaClass.simpleName}: ${it.message}") }
    }
    var remote =
      runCatching { client.listExtensions() }
        .onFailure { extLog.warn("读取扩展仓库清单失败：${it.javaClass.simpleName}: ${it.message}") }
        .getOrElse { throw SuwayomiException("读取扩展仓库清单失败：${it.message}", it) }
    if (remote.isEmpty() && !force) {
      runCatching { client.fetchExtensions() }
        .onFailure { extLog.warn("首次刷新扩展仓库清单失败：${it.javaClass.simpleName}: ${it.message}") }
      remote = runCatching { client.listExtensions() }.getOrDefault(emptyList())
    }
    val byPkg = remote.associateBy { it.pkgName }
    val wantedStore = store?.takeIf { it.isNotBlank() && it != "all" }
    val entries =
      remote
        .filter { wantedStore == null || it.storeIndexUrl == wantedStore }
        .map { it.toCatalogEntry() }
    val keywordFiltered =
      entries
        .asSequence()
        .filter { entry ->
          if (onlyInstalled) byPkg[entry.pkgName]?.isInstalled == true else true
        }.filter { entry ->
          keyword.isNullOrBlank() ||
            entry.name.contains(keyword, ignoreCase = true) ||
            entry.pkgName.contains(keyword, ignoreCase = true)
        }.toList()
    val languageCounts = mutableMapOf<String, Int>()
    keywordFiltered.forEach { entry ->
      entryLanguageCodes(entry.lang).forEach { code -> languageCounts[code] = (languageCounts[code] ?: 0) + 1 }
    }
    val filtered =
      keywordFiltered
        .filter { entry -> matchesLanguage(entry.lang, lang) }
        .sortedBy { it.name.lowercase() }
    val page = filtered.drop(offset.coerceAtLeast(0)).take(limit.coerceIn(1, 500))
    return SuwayomiCatalogPage(
      total = filtered.size,
      items =
        page.map { entry ->
          val match = byPkg[entry.pkgName]
          SuwayomiCatalogDto(
            pkgName = entry.pkgName,
            name = entry.name,
            versionName = entry.versionName,
            lang = entry.lang,
            iconUrl = entry.iconUrl,
            installed = match?.isInstalled == true,
            installedVersion = match?.versionName,
            hasUpdate = match?.hasUpdate == true && compareVersions(entry.versionName, match.versionName) > 0,
          )
        },
      languages =
        languageCounts.entries
          .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
          .map { SuwayomiCatalogLang(it.key, it.value) },
    )
  }

  fun installExtension(
    pkgName: String,
    route: String? = null,
  ): SuwayomiActionResult {
    val started = System.currentTimeMillis()
    val entry = remoteEntry(pkgName)
    if ((route.isNullOrBlank() || route == "auto") && selfInstall(pkgName, entry, update = false)) {
      return SuwayomiActionResult(true, "Installed ${entry.name} ${entry.versionName} by Suwayomi")
    }
    val bytes = installer.downloadExtensionJar(entry, route)
    client.installExternalExtension(bytes, extensionFileName(entry))
    logger.info { "Installed extension $pkgName (${bytes.size} bytes)" }
    extLog.info("安装插件 $pkgName ${entry.versionName}（兰台代下载 ${bytes.size} 字节，耗时 ${System.currentTimeMillis() - started}ms）")
    return SuwayomiActionResult(true, "Installed ${entry.name} ${entry.versionName}")
  }

  fun installExtensionPackage(
    bytes: ByteArray,
    fileName: String?,
  ): SuwayomiActionResult {
    if (bytes.isEmpty()) throw IllegalArgumentException("插件包为空")
    if (bytes.size > EXTENSION_PACKAGE_LIMIT_BYTES) throw IllegalArgumentException("插件包超过 64MB")
    if (bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) {
      throw IllegalArgumentException("不是有效的插件包（缺少 ZIP 头）")
    }
    val safeName = extensionPackageFileName(fileName)
    val data = client.installExternalExtension(bytes, safeName)
    val pkgName =
      data
        .path("installExternalExtension")
        .path("extension")
        .path("pkgName")
        .asText(safeName)
    logger.info { "Installed extension package $safeName as $pkgName (${bytes.size} bytes)" }
    extLog.info("导入插件包 $safeName → $pkgName（${bytes.size} 字节）")
    return SuwayomiActionResult(true, "Installed $pkgName")
  }

  fun uninstallExtension(pkgName: String): SuwayomiActionResult {
    client.patchExtension(pkgName, uninstall = true)
    extLog.info("卸载插件 $pkgName")
    return SuwayomiActionResult(true, "Uninstalled $pkgName")
  }

  fun updateExtension(
    pkgName: String,
    route: String? = null,
  ): SuwayomiActionResult {
    val entry = runCatching { remoteEntry(pkgName) }.getOrNull()
    if (entry == null) {
      client.patchExtension(pkgName, update = true)
      extLog.info("更新插件 $pkgName（交给服务端自行下载）")
      return SuwayomiActionResult(true, "Updated $pkgName")
    }
    if ((route.isNullOrBlank() || route == "auto") && selfInstall(pkgName, entry, update = true)) {
      return SuwayomiActionResult(true, "Updated ${entry.name} ${entry.versionName} by Suwayomi")
    }
    val bytes = installer.downloadExtensionJar(entry, route)
    client.installExternalExtension(bytes, extensionFileName(entry))
    extLog.info("更新插件 $pkgName → ${entry.versionName}（兰台代下载 ${bytes.size} 字节）")
    return SuwayomiActionResult(true, "Updated ${entry.name} ${entry.versionName}")
  }

  fun fetchExtensions(): SuwayomiActionResult {
    val repaired = repairStores()
    val node = client.fetchExtensions()
    val count = node.path("fetchExtensions").path("extensions").size()
    extLog.info("刷新已装插件列表，返回 $count 条" + if (repaired > 0) "，已修正 $repaired 个旧仓库地址" else "")
    return SuwayomiActionResult(true, "Refreshed $count installed extensions")
  }

  private fun repairStores(): Int {
    val urls =
      runCatching { client.listExtensionStores() }
        .onFailure { extLog.warn("读取扩展仓库失败：${it.message}") }
        .getOrDefault(emptyList())
        .mapNotNull { it.indexUrl }
    val legacy = urls.filter { it.startsWith(LEGACY_EXTENSION_STORE_PREFIX) }
    legacy.forEach { url ->
      runCatching { client.removeExtensionStore(url) }
        .onFailure { extLog.warn("移除旧扩展仓库失败 $url：${it.message}") }
    }
    if (legacy.isNotEmpty()) extLog.info("移除 ${legacy.size} 个旧扩展仓库地址")
    val target = properties.extensionIndexUrl.trim()
    if (target.isNotBlank() && target !in urls) {
      runCatching { client.addExtensionStore(target) }
        .onFailure { extLog.warn("添加默认扩展仓库失败 $target：${it.message}") }
    }
    return legacy.size
  }

  fun stores(): List<SuwayomiExtensionStore> = client.listExtensionStores()

  fun addStore(indexUrl: String): SuwayomiActionResult {
    client.addExtensionStore(indexUrl)
    extLog.info("添加扩展仓库 $indexUrl")
    return SuwayomiActionResult(true, "Added store $indexUrl")
  }

  fun removeStore(indexUrl: String): SuwayomiActionResult {
    client.removeExtensionStore(indexUrl)
    extLog.info("移除扩展仓库 $indexUrl")
    return SuwayomiActionResult(true, "Removed store $indexUrl")
  }

  fun downloadRoutes(): List<SuwayomiRouteDto> {
    val routes = mutableListOf(SuwayomiRouteDto("auto", "自动：优先 Suwayomi，失败依次回退"))
    properties.extensionDownloadMirrors
      .map { it.trim() }
      .filter { it.startsWith("http") }
      .forEachIndexed { index, prefix -> routes.add(SuwayomiRouteDto("mirror-${index + 1}", "镜像 ${index + 1} · ${hostOf(prefix)}")) }
    routes.add(SuwayomiRouteDto("github-api", "GitHub 资产直链"))
    routes.add(SuwayomiRouteDto("original", "原始地址"))
    return routes
  }

  private fun remoteEntry(pkgName: String): SuwayomiCatalogEntry =
    client.listExtensions().firstOrNull { it.pkgName == pkgName }?.toCatalogEntry()
      ?: throw SuwayomiException("Extension $pkgName not found in Suwayomi stores")

  private fun selfInstall(
    pkgName: String,
    entry: SuwayomiCatalogEntry,
    update: Boolean,
  ): Boolean {
    val url = entry.jarUrl ?: entry.apkUrl ?: return false
    if (!installer.canSuwayomiFetch(url)) return false
    val attempt =
      runCatching {
        if (update) client.patchExtension(pkgName, update = true) else client.patchExtension(pkgName, install = true)
      }
    if (attempt.isFailure) {
      extLog.warn("Suwayomi 自行下载插件 $pkgName 失败，转兰台代下载：${attempt.exceptionOrNull()?.message}")
      return false
    }
    val installed =
      runCatching { client.listExtensions().firstOrNull { it.pkgName == pkgName }?.isInstalled == true }
        .getOrDefault(false)
    if (!installed) {
      extLog.warn("Suwayomi 自行安装 $pkgName 后仍未标记为已装，转兰台代下载")
      return false
    }
    extLog.info("插件 $pkgName 由 Suwayomi 自行下载安装（${hostOf(url)}）")
    return true
  }

  private fun extensionFileName(entry: SuwayomiCatalogEntry): String =
    (entry.jarUrl ?: entry.apkUrl)?.substringAfterLast('/')?.substringBefore('?')
      ?: "${entry.pkgName}.jar"

  private fun hostOf(url: String): String = runCatching { java.net.URI(url).host }.getOrNull() ?: url

  fun start(): SuwayomiStatusDto {
    extLog.info("请求启动 Suwayomi")
    processManager.ensureRunning()
    return status()
  }

  fun stop(): SuwayomiStatusDto {
    extLog.info("请求停止 Suwayomi")
    processManager.stop()
    return status()
  }

  fun restart(): SuwayomiStatusDto {
    extLog.info("请求重启 Suwayomi")
    processManager.restart()
    return status()
  }

  fun latestRelease(): SuwayomiReleaseDto {
    val release = installer.latestRelease()
    return SuwayomiReleaseDto(
      tag = release.tag,
      publishedAt = release.publishedAt,
      assetName = release.assetName,
      size = release.size,
      installedVersion = processManager.status().version ?: installer.installedVersion(),
    )
  }

  fun installLatest(): SuwayomiStatusDto {
    val release = installer.latestRelease()
    if (!installer.isSupported(release.tag)) {
      throw SuwayomiException("Release ${release.tag} is outside the supported range ${properties.suwayomiMinVersion}..${properties.suwayomiMaxVersion}")
    }
    val running = processManager.status().running
    if (running) processManager.stop()
    installer.downloadLatest()
    return if (properties.suwayomiAutoStart) processManager.start().let { status() } else status()
  }

  fun importJar(bytes: ByteArray): SuwayomiStatusDto {
    val running = processManager.status().running
    if (running) processManager.stop()
    installer.importJar(bytes)
    return if (properties.suwayomiAutoStart) processManager.start().let { status() } else status()
  }

  fun rotatePassword(): SuwayomiActionResult {
    configWriter.rotatePassword()
    val running = processManager.status().running
    return if (running) {
      processManager.restart()
      SuwayomiActionResult(true, "Password rotated, Suwayomi restarted")
    } else {
      SuwayomiActionResult(true, "Password rotated, applies on next start")
    }
  }

  private fun extensionPackageFileName(fileName: String?): String {
    val base = fileName.orEmpty().substringAfterLast('/').substringAfterLast('\\').trim()
    val supported = base.endsWith(".jar", ignoreCase = true) || base.endsWith(".apk", ignoreCase = true)
    return if (base.isNotEmpty() && supported) base else DEFAULT_EXTENSION_PACKAGE_NAME
  }

  private fun compareVersions(
    left: String?,
    right: String?,
  ): Int {
    fun normalize(value: String?): List<Int> =
      value
        .orEmpty()
        .trim()
        .removePrefix("v")
        .split('.', '-', '+')
        .take(3)
        .mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }
    val l = normalize(left)
    val r = normalize(right)
    for (index in 0 until maxOf(l.size, r.size)) {
      val a = l.getOrElse(index) { 0 }
      val b = r.getOrElse(index) { 0 }
      if (a != b) return a.compareTo(b)
    }
    return 0
  }
}

private val LANGUAGE_ALIASES =
  mapOf(
    "中文" to "zh",
    "汉语" to "zh",
    "漢語" to "zh",
    "chinese" to "zh",
    "英文" to "en",
    "英语" to "en",
    "英語" to "en",
    "english" to "en",
    "日文" to "ja",
    "日语" to "ja",
    "日語" to "ja",
    "japanese" to "ja",
    "韩文" to "ko",
    "韩语" to "ko",
    "韓語" to "ko",
    "korean" to "ko",
    "泰文" to "th",
    "泰语" to "th",
    "thai" to "th",
    "西班牙文" to "es",
    "西班牙语" to "es",
    "spanish" to "es",
    "葡萄牙文" to "pt",
    "葡萄牙语" to "pt",
    "portuguese" to "pt",
    "法文" to "fr",
    "法语" to "fr",
    "french" to "fr",
    "德文" to "de",
    "德语" to "de",
    "german" to "de",
    "俄文" to "ru",
    "俄语" to "ru",
    "russian" to "ru",
    "印尼文" to "id",
    "印尼语" to "id",
    "indonesian" to "id",
    "越南文" to "vi",
    "越南语" to "vi",
    "vietnamese" to "vi",
    "阿拉伯文" to "ar",
    "阿拉伯语" to "ar",
    "arabic" to "ar",
    "多语言" to "multi",
    "多語言" to "multi",
    "multilingual" to "multi",
  )

private fun languageKey(raw: String?): String? {
  val value = raw?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
  LANGUAGE_ALIASES[value]?.let { return it }
  return value.split('-', '_').first().trim().ifBlank { null }
}

private fun entryLanguageCodes(raw: String?): List<String> =
  raw?.split(',')?.mapNotNull { languageKey(it) }?.filter { it.isNotBlank() }?.distinct().orEmpty()

private fun matchesLanguage(
  entryLang: String?,
  wanted: String?,
): Boolean {
  val target = languageKey(wanted) ?: return true
  val parts = entryLanguageCodes(entryLang)
  if (parts.isEmpty()) return false
  if (parts.contains(target)) return true
  return target == "multi" && (parts.contains("all") || parts.contains("multi"))
}

private fun SuwayomiExtension.toCatalogEntry() =
  SuwayomiCatalogEntry(
    pkgName = pkgName,
    name = name ?: pkgName,
    versionName = versionName.orEmpty(),
    lang = lang.orEmpty(),
    versionCode = 0,
    jarUrl = jarUrl,
    apkUrl = apkUrl,
    iconUrl = iconUrl,
  )
