package org.lantai.infrastructure.metadata.scrape

import org.lantai.infrastructure.jooq.main.ServerSettingsDao
import org.springframework.stereotype.Service

data class ScrapeSettings(
  val trustResult: Boolean,
  val batchSize: Int,
  val conflictPolicy: String,
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

  var conflictPolicy: String =
    normalizePolicy(serverSettingsDao.getSettingByKey(KEY_CONFLICT_POLICY, String::class.java))
    set(value) {
      val normalized = normalizePolicy(value)
      serverSettingsDao.saveSetting(KEY_CONFLICT_POLICY, normalized)
      field = normalized
    }

  fun snapshot() =
    ScrapeSettings(
      trustResult = trustResult,
      batchSize = batchSize,
      conflictPolicy = conflictPolicy,
    )

  fun effectivePolicy(trust: Boolean): String = if (trust) CONFLICT_POLICY_OVERWRITE else conflictPolicy

  companion object {
    const val CONFLICT_POLICY_REVIEW = "review"
    const val CONFLICT_POLICY_OVERWRITE = "overwrite"
    const val CONFLICT_POLICY_KEEP = "keep"
    val CONFLICT_POLICIES = setOf(CONFLICT_POLICY_REVIEW, CONFLICT_POLICY_OVERWRITE, CONFLICT_POLICY_KEEP)

    fun normalizePolicy(raw: String?): String =
      raw?.trim()?.lowercase()?.takeIf { it in CONFLICT_POLICIES } ?: CONFLICT_POLICY_REVIEW

    private const val KEY_TRUST_RESULT = "SCRAPE_TRUST_RESULT"
    private const val KEY_BATCH_SIZE = "SCRAPE_BATCH_SIZE"
    private const val KEY_CONFLICT_POLICY = "SCRAPE_CONFLICT_POLICY"
    private const val DEFAULT_BATCH_SIZE = 20
    private const val MIN_BATCH_SIZE = 1
    private const val MAX_BATCH_SIZE = 200
  }
}
