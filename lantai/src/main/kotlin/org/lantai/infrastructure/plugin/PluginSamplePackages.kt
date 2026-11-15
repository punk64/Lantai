package org.lantai.infrastructure.plugin

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.f4b6a3.tsid.TsidCreator
import org.springframework.core.io.Resource
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.stereotype.Component
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream

@Component
class PluginSamplePackages(
  private val paths: PluginPaths,
) {
  private val mapper = ObjectMapper()

  fun list(): List<PluginSample> =
    resources().mapNotNull { resource ->
      val id = resource.filename.orEmpty().removeSuffix(".zip")
      runCatching {
        val manifest = resource.inputStream.use { readManifest(it) }
        PluginSample(
          id = id,
          pluginId = manifest.pluginId,
          name = manifest.name,
          version = manifest.version,
          description = manifest.description,
          sizeBytes = runCatching { resource.contentLength() }.getOrDefault(0),
        )
      }.getOrNull()
    }.sortedBy { it.name }

  fun stage(sampleId: String): Path {
    val wanted = sampleId.trim()
    val resource =
      resources().firstOrNull { it.filename.orEmpty().removeSuffix(".zip") == wanted }
        ?: throw PluginNotFoundException("内置示例插件不存在：$wanted")
    val dir = paths.downloadDir()
    Files.createDirectories(dir)
    val target = dir.resolve("sample-" + TsidCreator.getTsid256().toString().lowercase() + ".zip")
    resource.inputStream.use { input -> Files.copy(input, target) }
    return target
  }

  private fun resources(): List<Resource> =
    runCatching {
      PathMatchingResourcePatternResolver().getResources("classpath*:plugin-samples/*.zip").toList()
    }.getOrDefault(emptyList())

  private fun readManifest(stream: InputStream): SampleManifest =
    ZipInputStream(stream).use { zip ->
      while (true) {
        val entry = zip.nextEntry ?: break
        if (entry.name == "plugin.json") {
          val node = mapper.readTree(zip)
          return SampleManifest(
            pluginId = node.path("id").asText(),
            name = node.path("name").asText(),
            version = node.path("version").asText(),
            description = node.path("description").asText(),
          )
        }
        zip.closeEntry()
      }
      throw PluginRequestException("插件包缺少 plugin.json")
    }
}

data class PluginSample(
  val id: String,
  val pluginId: String,
  val name: String,
  val version: String,
  val description: String,
  val sizeBytes: Long,
)

private data class SampleManifest(
  val pluginId: String,
  val name: String,
  val version: String,
  val description: String,
)
