package org.lantai.interfaces.scheduler

import io.github.oshai.kotlinlogging.KotlinLogging
import org.lantai.domain.persistence.LibraryRepository
import org.lantai.domain.service.TrashBin
import org.lantai.infrastructure.configuration.LantaiSettingsProvider
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class TrashAutoCleaner(
  private val trashBin: TrashBin,
  private val libraryRepository: LibraryRepository,
  private val lantaiSettingsProvider: LantaiSettingsProvider,
) {
  @Scheduled(fixedDelay = 3_600_000)
  fun clean() {
    if (!lantaiSettingsProvider.trashAutoClean) return

    val maxBytesDefault = lantaiSettingsProvider.trashMaxBytesDefault
    libraryRepository.findAll().forEach { library ->
      if (trashBin.isEnabled(library, maxBytesDefault)) {
        runCatching { trashBin.enforceQuota(library, maxBytesDefault) }
          .onFailure { logger.error(it) { "Failed to enforce trash quota for library ${library.id}" } }
      }
    }

    runCatching { trashBin.purgeExpired(lantaiSettingsProvider.trashRetentionHours) }
      .onFailure { logger.error(it) { "Failed to purge expired trash entries" } }
  }
}
