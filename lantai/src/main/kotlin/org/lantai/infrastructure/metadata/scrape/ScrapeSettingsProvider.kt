package org.lantai.infrastructure.metadata.scrape

import org.lantai.infrastructure.jooq.main.ServerSettingsDao
import org.springframework.stereotype.Service

data class ScrapeSettings(
  val trustResult: Boolean,
  val batchSize: Int,
)

@Service
class ScrapeSettingsProvider(
  private val serverSettingsDao: ServerSettingsDao,
) {
  var trustResult: Boolean =
    serverSettingsDao.getSettingByKey(KEY_TRUST_RESULT, Boolean::class.java) ?: false
    set(value) {
      serverSettingsDao.saveSetting(KEY_TRUST_RESULT, value)
      field = value
    }

  var batchSize: Int =
    (serverSettingsDao.getSettingByKey(KEY_BATCH_SIZE, Int::class.java) ?: DEFAULT_BATCH_SIZE)
      .coerceIn(MIN_BATCH_SIZE, MAX_BATCH_SIZE)
    set(value) {
      val normalized = value.coerceIn(MIN_BATCH_SIZE, MAX_BATCH_SIZE)
      serverSettingsDao.saveSetting(KEY_BATCH_SIZE, normalized)
      field = normalized
    }

  fun snapshot() = ScrapeSettings(trustResult = trustResult, batchSize = batchSize)

  private companion object {
    const val KEY_TRUST_RESULT = "SCRAPE_TRUST_RESULT"
    const val KEY_BATCH_SIZE = "SCRAPE_BATCH_SIZE"
    const val DEFAULT_BATCH_SIZE = 20
    const val MIN_BATCH_SIZE = 1
    const val MAX_BATCH_SIZE = 200
  }
}
