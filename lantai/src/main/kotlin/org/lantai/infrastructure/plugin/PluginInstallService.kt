package org.lantai.infrastructure.plugin

import com.github.f4b6a3.tsid.TsidCreator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private val logger = KotlinLogging.logger {}

const val INSTALL_JOB_INSTALL = "install"
const val INSTALL_JOB_UPGRADE = "upgrade"

@Service
class PluginInstallService(
  private val instances: PluginInstanceService,
  private val repositories: PluginRepositoryClient,
  private val jobs: PluginInstallJobs,
  private val paths: PluginPaths,
) {
  private val executor: ExecutorService =
    Executors.newSingleThreadExecutor { runnable ->
      Thread(runnable, "lantai-plugin-install").apply { isDaemon = true }
    }

  fun stage(file: MultipartFile): Path {
    if (file.isEmpty) throw PluginRequestException("插件包为空")
    if (!file.originalFilename.orEmpty().lowercase().endsWith(".zip")) throw PluginRequestException("插件包必须是 zip 文件")
    val dir = paths.downloadDir()
    Files.createDirectories(dir)
    val target = dir.resolve("upload-" + TsidCreator.getTsid256().toString().lowercase() + ".zip")
    return try {
      file.inputStream.use { input -> Files.copy(input, target) }
      target
    } catch (ex: Exception) {
      runCatching { Files.deleteIfExists(target) }
      throw ex
    }
  }

  fun submitInstall(
    file: MultipartFile,
    ownerId: String,
  ): String {
    val staged = stage(file)
    val jobId = jobs.create(INSTALL_JOB_INSTALL, "正在安装插件")
    val listener = jobs.listener(jobId)
    listener.on(PHASE_UPLOAD, 100, "插件包已接收，开始解压")
    executor.execute {
      try {
        val instance = instances.install(staged.toString(), ownerId, listener)
        jobs.done(jobId, instance, "已安装 " + instance.name + " " + instance.version)
      } catch (ex: Exception) {
        logger.warn(ex) { "Plugin install job $jobId failed" }
        jobs.fail(jobId, ex.message ?: "安装失败")
      } finally {
        runCatching { Files.deleteIfExists(staged) }
      }
    }
    return jobId
  }

  fun submitInstallFromRepository(
    indexUrl: String,
    pluginId: String,
    version: String?,
    ownerId: String,
  ): String {
    val jobId = jobs.create(INSTALL_JOB_INSTALL, "正在准备安装")
    val listener = jobs.listener(jobId)
    listener.on(PHASE_DOWNLOAD, -1, "正在读取仓库索引")
    executor.execute {
      try {
        val entry = findEntry(indexUrl, pluginId, version)
        val instance = instances.installFromIndex(entry, ownerId, listener)
        jobs.done(jobId, instance, "已安装 " + instance.name + " " + instance.version)
      } catch (ex: Exception) {
        logger.warn(ex) { "Plugin repository install job $jobId failed" }
        jobs.fail(jobId, ex.message ?: "安装失败")
      }
    }
    return jobId
  }

  fun submitUpgrade(
    instanceId: String,
    file: MultipartFile,
  ): String {
    val staged = stage(file)
    val jobId = jobs.create(INSTALL_JOB_UPGRADE, "正在升级插件")
    val listener = jobs.listener(jobId)
    listener.on(PHASE_UPLOAD, 100, "插件包已接收，开始解压")
    executor.execute {
      try {
        val instance = instances.upgrade(instanceId, staged.toString(), listener)
        jobs.done(jobId, instance, "已升级到 " + instance.version)
      } catch (ex: Exception) {
        logger.warn(ex) { "Plugin upgrade job $jobId failed" }
        jobs.fail(jobId, ex.message ?: "升级失败")
      } finally {
        runCatching { Files.deleteIfExists(staged) }
      }
    }
    return jobId
  }

  fun submitUpgradeFromRepository(
    instanceId: String,
    indexUrl: String,
    pluginId: String,
    version: String?,
  ): String {
    val jobId = jobs.create(INSTALL_JOB_UPGRADE, "正在准备升级")
    val listener = jobs.listener(jobId)
    listener.on(PHASE_DOWNLOAD, -1, "正在读取仓库索引")
    executor.execute {
      try {
        val entry = findEntry(indexUrl, pluginId, version)
        val instance = instances.upgradeFromIndex(instanceId, entry, listener)
        jobs.done(jobId, instance, "已升级到 " + instance.version)
      } catch (ex: Exception) {
        logger.warn(ex) { "Plugin repository upgrade job $jobId failed" }
        jobs.fail(jobId, ex.message ?: "升级失败")
      }
    }
    return jobId
  }

  fun activeJobs(): List<PluginInstallJob> = jobs.active()

  fun recentJobs(): List<PluginInstallJob> = jobs.recent(RECENT_MINUTES)

  fun job(jobId: String): PluginInstallJob? = jobs.get(jobId)

  fun dismiss(jobId: String) = jobs.dismiss(jobId)

  private fun findEntry(
    indexUrl: String,
    pluginId: String,
    version: String?,
  ): PluginIndexEntry {
    val index = repositories.fetchIndex(indexUrl)
    return index.entries.firstOrNull { it.pluginId == pluginId && (version.isNullOrBlank() || it.version == version) }
      ?: throw PluginNotFoundException("索引中没有插件 $pluginId ${version.orEmpty()}".trim())
  }

  private companion object {
    private const val RECENT_MINUTES = 5L
  }
}
