package org.lantai.domain.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.lantai.application.scheduler.LibraryScanScheduler
import org.lantai.application.tasks.HIGHEST_PRIORITY
import org.lantai.application.tasks.LOWEST_PRIORITY
import org.lantai.application.tasks.TaskEmitter
import org.lantai.domain.model.DirectoryNotFoundException
import org.lantai.domain.model.DomainEvent
import org.lantai.domain.model.DuplicateNameException
import org.lantai.domain.model.Library
import org.lantai.domain.model.PathContainedInPath
import org.lantai.domain.persistence.LibraryRepository
import org.lantai.domain.persistence.SeriesRepository
import org.lantai.domain.persistence.SidecarRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.io.FileNotFoundException
import java.nio.file.Files

private val logger = KotlinLogging.logger {}
private const val DELETE_BATCH = 100

@Service
class LibraryLifecycle(
  private val libraryRepository: LibraryRepository,
  private val seriesLifecycle: SeriesLifecycle,
  private val seriesRepository: SeriesRepository,
  private val sidecarRepository: SidecarRepository,
  private val taskEmitter: TaskEmitter,
  private val eventPublisher: ApplicationEventPublisher,
  private val transactionTemplate: TransactionTemplate,
  private val libraryScanScheduler: LibraryScanScheduler,
) {
  @Throws(
    FileNotFoundException::class,
    DirectoryNotFoundException::class,
    DuplicateNameException::class,
    PathContainedInPath::class,
  )
  fun addLibrary(library: Library): Library {
    logger.info { "Adding new library: ${library.name} with root folder: ${library.root}" }

    val existing = libraryRepository.findAll()
    checkLibraryValidity(library, existing)

    libraryRepository.insert(library)
    taskEmitter.scanLibrary(library.id, priority = HIGHEST_PRIORITY)

    eventPublisher.publishEvent(DomainEvent.LibraryAdded(library))

    return libraryRepository.findById(library.id)
  }

  fun updateLibrary(toUpdate: Library) {
    logger.info { "Updating library: ${toUpdate.id}" }

    val libraries = libraryRepository.findAll()
    val otherLibraries = libraries.filter { it.id != toUpdate.id }
    val current = libraries.first { it.id == toUpdate.id }
    checkLibraryValidity(toUpdate, otherLibraries)

    libraryRepository.update(toUpdate)

    if (current.scanInterval != toUpdate.scanInterval)
      libraryScanScheduler.scheduleScan(toUpdate)

    if (checkLibraryShouldRescan(current, toUpdate)) {
      if (current.root != toUpdate.root)
        taskEmitter.scanLibrary(toUpdate.id, priority = HIGHEST_PRIORITY)
      else
        taskEmitter.scanLibrary(toUpdate.id)
    }

    if (toUpdate.hashFiles && !current.hashFiles)
      taskEmitter.hashBooksWithoutHash(toUpdate)
    if (toUpdate.hashKoreader && !current.hashKoreader)
      taskEmitter.hashBooksWithoutHashKoreader(toUpdate)
    if (toUpdate.hashPages && !current.hashPages)
      taskEmitter.findBooksWithMissingPageHash(toUpdate, LOWEST_PRIORITY)
    if (toUpdate.repairExtensions && !current.repairExtensions)
      taskEmitter.repairExtensions(toUpdate, LOWEST_PRIORITY)
    if (toUpdate.convertToCbz && !current.convertToCbz)
      taskEmitter.findBooksToConvert(toUpdate, LOWEST_PRIORITY)

    eventPublisher.publishEvent(DomainEvent.LibraryUpdated(toUpdate))
  }

  private fun checkLibraryShouldRescan(
    existing: Library,
    updated: Library,
  ): Boolean {
    if (existing.root != updated.root) return true
    if (existing.oneshotsDirectory != updated.oneshotsDirectory) return true
    if (existing.scanCbx != updated.scanCbx) return true
    if (existing.scanPdf != updated.scanPdf) return true
    if (existing.scanEpub != updated.scanEpub) return true
    if (existing.scanTxt != updated.scanTxt) return true
    if (existing.scanForceModifiedTime != updated.scanForceModifiedTime) return true
    if (existing.scanDirectoryExclusions != updated.scanDirectoryExclusions) return true
    return false
  }

  private fun checkLibraryValidity(
    library: Library,
    existing: Collection<Library>,
  ) {
    if (!Files.exists(library.path))
      throw FileNotFoundException("Library root folder does not exist: ${library.root}")

    if (!Files.isDirectory(library.path))
      throw DirectoryNotFoundException("Library root folder is not a folder: ${library.root}")

    if (existing.map { it.name }.contains(library.name))
      throw DuplicateNameException("Library name already exists")

    existing.forEach {
      if (library.path.startsWith(it.path))
        throw PathContainedInPath("Library path ${library.path} is a child of existing library ${it.name}: ${it.path}")
      if (it.path.startsWith(library.path))
        throw PathContainedInPath("Library path ${library.path} is a parent of existing library ${it.name}: ${it.path}")
    }
  }

  fun deleteLibrary(library: Library) {
    logger.info { "Deleting library: $library" }

    val series = seriesRepository.findAllByLibraryId(library.id)
    logger.info { "Deleting ${series.size} series in batches of $DELETE_BATCH" }
    series.chunked(DELETE_BATCH).forEach { batch -> seriesLifecycle.deleteMany(batch) }

    transactionTemplate.executeWithoutResult {
      sidecarRepository.deleteByLibraryId(library.id)

      libraryRepository.delete(library.id)
    }

    eventPublisher.publishEvent(DomainEvent.LibraryDeleted(library))
  }
}
