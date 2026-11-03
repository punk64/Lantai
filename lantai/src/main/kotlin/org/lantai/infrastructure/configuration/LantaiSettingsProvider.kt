package org.lantai.infrastructure.configuration

import org.apache.commons.lang3.RandomStringUtils
import org.lantai.domain.model.ThumbnailSize
import org.lantai.infrastructure.jooq.main.ServerSettingsDao
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

private fun defaultTaskPoolSize(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 8)

@Service
class LantaiSettingsProvider(
  private val serverSettingsDao: ServerSettingsDao,
  private val eventPublisher: ApplicationEventPublisher,
) {
  var deleteEmptyCollections: Boolean =
    serverSettingsDao.getSettingByKey(Settings.DELETE_EMPTY_COLLECTIONS.name, Boolean::class.java) ?: false
    set(value) {
      serverSettingsDao.saveSetting(Settings.DELETE_EMPTY_COLLECTIONS.name, value)
      field = value
    }

  var deleteEmptyReadLists: Boolean =
    serverSettingsDao.getSettingByKey(Settings.DELETE_EMPTY_READLISTS.name, Boolean::class.java) ?: false
    set(value) {
      serverSettingsDao.saveSetting(Settings.DELETE_EMPTY_READLISTS.name, value)
      field = value
    }

  var rememberMeKey: String =
    serverSettingsDao.getSettingByKey(Settings.REMEMBER_ME_KEY.name, String::class.java)
      ?: getRandomRememberMeKey().also { rememberMeKey = it }
    set(value) {
      serverSettingsDao.saveSetting(Settings.REMEMBER_ME_KEY.name, value)
      field = value
    }

  fun renewRememberMeKey() {
    rememberMeKey = getRandomRememberMeKey()
  }

  private fun getRandomRememberMeKey() = RandomStringUtils.secure().nextAlphanumeric(32)

  var rememberMeDuration: Duration =
    (serverSettingsDao.getSettingByKey(Settings.REMEMBER_ME_DURATION.name, Int::class.java) ?: 365).days
    set(value) {
      serverSettingsDao.saveSetting(Settings.REMEMBER_ME_DURATION.name, value.inWholeDays.toInt())
      field = value
    }

  var thumbnailSize: ThumbnailSize =
    serverSettingsDao.getSettingByKey(Settings.THUMBNAIL_SIZE.name, String::class.java)?.let {
      ThumbnailSize.valueOf(it)
    } ?: ThumbnailSize.DEFAULT
    set(value) {
      serverSettingsDao.saveSetting(Settings.THUMBNAIL_SIZE.name, value.name)
      field = value
    }

  var taskPoolSize: Int =
    serverSettingsDao.getSettingByKey(Settings.TASK_POOL_SIZE.name, Int::class.java) ?: defaultTaskPoolSize()
    set(value) {
      serverSettingsDao.saveSetting(Settings.TASK_POOL_SIZE.name, value)
      field = value
      eventPublisher.publishEvent(SettingChangedEvent.TaskPoolSize)
    }

  var serverPort: Int? =
    serverSettingsDao.getSettingByKey(Settings.SERVER_PORT.name, Int::class.java)
    set(value) {
      if (value != null)
        serverSettingsDao.saveSetting(Settings.SERVER_PORT.name, value)
      else
        serverSettingsDao.deleteSetting(Settings.SERVER_PORT.name)
      field = value
    }

  var trashMaxBytesDefault: Long =
    serverSettingsDao.getSettingByKey(Settings.TRASH_MAX_BYTES_DEFAULT.name, Long::class.java) ?: 0L
    set(value) {
      serverSettingsDao.saveSetting(Settings.TRASH_MAX_BYTES_DEFAULT.name, value)
      field = value
    }

  var trashAutoClean: Boolean =
    serverSettingsDao.getSettingByKey(Settings.TRASH_AUTO_CLEAN.name, Boolean::class.java) ?: false
    set(value) {
      serverSettingsDao.saveSetting(Settings.TRASH_AUTO_CLEAN.name, value)
      field = value
    }

  var trashRetentionHours: Int =
    serverSettingsDao.getSettingByKey(Settings.TRASH_RETENTION_HOURS.name, Int::class.java) ?: 168
    set(value) {
      serverSettingsDao.saveSetting(Settings.TRASH_RETENTION_HOURS.name, value)
      field = value
    }

  var serverContextPath: String? =
    serverSettingsDao.getSettingByKey(Settings.SERVER_CONTEXT_PATH.name, String::class.java)
    set(value) {
      if (value != null)
        serverSettingsDao.saveSetting(Settings.SERVER_CONTEXT_PATH.name, value)
      else
        serverSettingsDao.deleteSetting(Settings.SERVER_CONTEXT_PATH.name)
      field = value
    }

  var koboProxy: Boolean =
    serverSettingsDao.getSettingByKey(Settings.KOBO_PROXY.name, Boolean::class.java) ?: false
    set(value) {
      serverSettingsDao.saveSetting(Settings.KOBO_PROXY.name, value)
      field = value
    }

  var koboPort: Int? =
    serverSettingsDao.getSettingByKey(Settings.KOBO_PORT.name, Int::class.java)
    set(value) {
      if (value != null)
        serverSettingsDao.saveSetting(Settings.KOBO_PORT.name, value)
      else
        serverSettingsDao.deleteSetting(Settings.KOBO_PORT.name)
      field = value
    }

  var kepubifyPath: String? =
    serverSettingsDao.getSettingByKey(Settings.KEPUBIFY_PATH.name, String::class.java)?.ifBlank { null }
    set(value) {
      if (value != null)
        serverSettingsDao.saveSetting(Settings.KEPUBIFY_PATH.name, value)
      else
        serverSettingsDao.deleteSetting(Settings.KEPUBIFY_PATH.name)
      field = value
      eventPublisher.publishEvent(SettingChangedEvent.KepubifyPath)
    }

  var logLevels: Map<String, String> =
    serverSettingsDao
      .getSettingByKey(Settings.LOG_LEVELS.name, String::class.java)
      ?.split(';')
      ?.mapNotNull { entry ->
        val index = entry.indexOf('=')
        if (index <= 0) null else entry.substring(0, index) to entry.substring(index + 1)
      }?.toMap()
      ?: emptyMap()
    set(value) {
      if (value.isEmpty()) {
        serverSettingsDao.deleteSetting(Settings.LOG_LEVELS.name)
      } else {
        serverSettingsDao.saveSetting(
          Settings.LOG_LEVELS.name,
          value.entries.joinToString(";") { "${it.key}=${it.value}" },
        )
      }
      field = value
    }

  var calibreExecutablePath: String =
    serverSettingsDao.getSettingByKey(Settings.CALIBRE_EXECUTABLE_PATH.name, String::class.java) ?: ""
    set(value) {
      if (value.isBlank()) {
        serverSettingsDao.deleteSetting(Settings.CALIBRE_EXECUTABLE_PATH.name)
      } else {
        serverSettingsDao.saveSetting(Settings.CALIBRE_EXECUTABLE_PATH.name, value)
      }
      field = value
    }

  var calibreConvertMode: String =
    serverSettingsDao.getSettingByKey(Settings.CALIBRE_CONVERT_MODE.name, String::class.java) ?: ""
    set(value) {
      if (value.isBlank()) {
        serverSettingsDao.deleteSetting(Settings.CALIBRE_CONVERT_MODE.name)
      } else {
        serverSettingsDao.saveSetting(Settings.CALIBRE_CONVERT_MODE.name, value)
      }
      field = value
    }

  var calibreEnabled: Boolean? =
    serverSettingsDao.getSettingByKey(Settings.CALIBRE_ENABLED.name, Boolean::class.java)
    set(value) {
      if (value != null) {
        serverSettingsDao.saveSetting(Settings.CALIBRE_ENABLED.name, value)
      } else {
        serverSettingsDao.deleteSetting(Settings.CALIBRE_ENABLED.name)
      }
      field = value
    }

  var suwayomiEnabled: Boolean? =
    serverSettingsDao.getSettingByKey(Settings.SUWAYOMI_ENABLED.name, Boolean::class.java)
    set(value) {
      if (value != null) {
        serverSettingsDao.saveSetting(Settings.SUWAYOMI_ENABLED.name, value)
      } else {
        serverSettingsDao.deleteSetting(Settings.SUWAYOMI_ENABLED.name)
      }
      field = value
    }

  var onlineDisabledSources: Set<String> =
    serverSettingsDao
      .getSettingByKey(Settings.ONLINE_DISABLED_SOURCES.name, String::class.java)
      ?.split(',')
      ?.filter { it.isNotBlank() }
      ?.toSet()
      ?: emptySet()
    set(value) {
      if (value.isEmpty()) {
        serverSettingsDao.deleteSetting(Settings.ONLINE_DISABLED_SOURCES.name)
      } else {
        serverSettingsDao.saveSetting(Settings.ONLINE_DISABLED_SOURCES.name, value.joinToString(","))
      }
      field = value
    }
}

private enum class Settings {
  DELETE_EMPTY_COLLECTIONS,
  DELETE_EMPTY_READLISTS,
  REMEMBER_ME_KEY,
  REMEMBER_ME_DURATION,
  THUMBNAIL_SIZE,
  TASK_POOL_SIZE,
  SERVER_PORT,
  SERVER_CONTEXT_PATH,
  KOBO_PROXY,
  KOBO_PORT,
  KEPUBIFY_PATH,
  TRASH_MAX_BYTES_DEFAULT,
  TRASH_AUTO_CLEAN,
  TRASH_RETENTION_HOURS,
  LOG_LEVELS,
  CALIBRE_EXECUTABLE_PATH,
  CALIBRE_CONVERT_MODE,
  CALIBRE_ENABLED,
  SUWAYOMI_ENABLED,
  ONLINE_DISABLED_SOURCES,
}
