package org.lantai.infrastructure.online

import io.github.oshai.kotlinlogging.KotlinLogging
import org.lantai.domain.model.Library
import org.lantai.domain.persistence.LibraryRepository
import org.lantai.domain.service.LibraryLifecycle
import org.lantai.infrastructure.configuration.LantaiProperties
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists

private val logger = KotlinLogging.logger {}

@Service
class OnlineLibraryService(
  private val properties: OnlineProperties,
  private val lantaiProperties: LantaiProperties,
  private val libraryLifecycle: LibraryLifecycle,
  private val libraryRepository: LibraryRepository,
  private val registryProvider: ObjectProvider<OnlineSourceRegistry>,
) {
  fun available(): Boolean =
    runCatching { registryProvider.getIfAvailable()?.summary()?.ready == true }.getOrDefault(false)

  fun findByType(): Library? = libraryRepository.findAll().firstOrNull { it.type == Library.Type.ONLINE }

  fun ensureLibrary(): Library =
    findByType() ?: run {
      val root = rootDir()
      Files.createDirectories(root)
      logger.info { "Creating online library at $root" }
      libraryLifecycle.addLibrary(
        Library(
          name = properties.libraryName,
          root = root.toUri().toURL(),
          type = Library.Type.ONLINE,
          scanInterval = Library.ScanInterval.DISABLED,
          scanOnStartup = false,
          scanCbx = false,
          scanPdf = false,
          scanEpub = false,
          scanTxt = false,
          hashFiles = false,
          hashPages = false,
          hashKoreader = false,
          analyzeDimensions = false,
          importLocalArtwork = false,
          importBarcodeIsbn = false,
        ),
      )
    }

  fun seriesDir(sourceName: String, sourceLang: String, mangaTitle: String): Path {
    val dir = rootDir().resolve("$sourceName ($sourceLang)").resolve(sanitize(mangaTitle))
    Files.createDirectories(dir)
    return dir
  }

  fun chapterPlaceholder(
    sourceName: String,
    sourceLang: String,
    mangaTitle: String,
    chapterTitle: String,
  ): Path = chapterPlaceholder(seriesDir(sourceName, sourceLang, mangaTitle), chapterTitle)

  fun thumbnailDir(): Path {
    val dir = rootDir().resolve(".thumbnails")
    Files.createDirectories(dir)
    return dir
  }

  fun chapterPlaceholder(
    directory: Path,
    chapterTitle: String,
  ): Path {
    val file = directory.resolve("${sanitize(chapterTitle)}.placeholder")
    if (!file.exists()) Files.createFile(file)
    return file
  }

  @EventListener(ApplicationReadyEvent::class)
  fun onApplicationReady() {
    if (!available()) return
    try {
      val library = ensureLibrary()
      logger.info { "Online library ready: ${library.name} (${library.root})" }
    } catch (e: Exception) {
      logger.error(e) { "Failed to create online library" }
    }
  }

  private fun rootDir(): Path {
    val configured = properties.rootDir
    if (configured.isNotBlank()) return Paths.get(configured).toAbsolutePath().normalize()
    val configDir = lantaiProperties.configDir ?: "."
    val configPath = Paths.get(configDir).toAbsolutePath().normalize()
    val parent = configPath.parent ?: configPath
    return parent.resolve("online-library")
  }

  private fun sanitize(name: String): String =
    name.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifBlank { "untitled" }
}
