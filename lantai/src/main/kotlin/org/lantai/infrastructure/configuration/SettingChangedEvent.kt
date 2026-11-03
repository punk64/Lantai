package org.lantai.infrastructure.configuration

sealed class SettingChangedEvent {
  data object TaskPoolSize : SettingChangedEvent()

  data object KepubifyPath : SettingChangedEvent()
}
