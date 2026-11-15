package org.lantai.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.lantai.infrastructure.configuration.LantaiSettingsProvider
import org.lantai.infrastructure.kobo.KepubConverter
import org.lantai.infrastructure.openapi.OpenApiConfiguration
import org.lantai.infrastructure.security.LantaiPrincipal
import org.lantai.infrastructure.web.WebServerEffectiveSettings
import org.lantai.interfaces.api.rest.dto.SettingMultiSource
import org.lantai.interfaces.api.rest.dto.SettingsDto
import org.lantai.interfaces.api.rest.dto.SettingsUpdateDto
import org.lantai.interfaces.api.rest.dto.public
import org.lantai.interfaces.api.rest.dto.toDomain
import org.lantai.interfaces.api.rest.dto.toDto
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import kotlin.time.Duration.Companion.days

@RestController
@RequestMapping(value = ["api/v1/settings"], produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = OpenApiConfiguration.TagNames.SERVER_SETTINGS)
class SettingsController(
  private val lantaiSettingsProvider: LantaiSettingsProvider,
  @param:Value($$"${server.port:#{null}}") private val configServerPort: Int?,
  @param:Value($$"${server.servlet.context-path:#{null}}") private val configServerContextPath: String?,
  private val serverSettings: WebServerEffectiveSettings,
  private val kepubConverter: KepubConverter,
  private val multipartProperties: MultipartProperties,
) {
  @GetMapping
  @Operation(summary = "Retrieve server settings")
  fun getServerSettings(
    @AuthenticationPrincipal principal: LantaiPrincipal,
  ): SettingsDto {
    val settingsDto =
      SettingsDto(
        lantaiSettingsProvider.deleteEmptyCollections,
        lantaiSettingsProvider.deleteEmptyReadLists,
        lantaiSettingsProvider.rememberMeDuration.inWholeDays,
        lantaiSettingsProvider.thumbnailSize.toDto(),
        lantaiSettingsProvider.taskPoolSize,
        SettingMultiSource(configServerPort, lantaiSettingsProvider.serverPort, serverSettings.effectiveServerPort),
        SettingMultiSource(configServerContextPath, lantaiSettingsProvider.serverContextPath, serverSettings.effectiveServletContextPath),
        lantaiSettingsProvider.koboProxy,
        lantaiSettingsProvider.koboPort,
        SettingMultiSource(kepubConverter.kepubifyConfigurationPath, lantaiSettingsProvider.kepubifyPath, kepubConverter.kepubifyPath?.toString()),
        multipartProperties.maxFileSize?.toBytes(),
      )

    return if (principal.user.isAdmin) settingsDto else settingsDto.public()
  }

  @PatchMapping
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Update server settings", description = "You can omit fields you don't want to update")
  @PreAuthorize("hasRole('ADMIN')")
  fun updateServerSettings(
    @Valid @RequestBody
    @Parameter(description = "Fields to update. You can omit fields you don't want to update.")
    newSettings: SettingsUpdateDto,
  ) {
    newSettings.deleteEmptyCollections?.let { lantaiSettingsProvider.deleteEmptyCollections = it }
    newSettings.deleteEmptyReadLists?.let { lantaiSettingsProvider.deleteEmptyReadLists = it }
    newSettings.rememberMeDurationDays?.let { lantaiSettingsProvider.rememberMeDuration = it.days }
    if (newSettings.renewRememberMeKey == true) lantaiSettingsProvider.renewRememberMeKey()
    newSettings.thumbnailSize?.let { lantaiSettingsProvider.thumbnailSize = it.toDomain() }
    newSettings.taskPoolSize?.let { lantaiSettingsProvider.taskPoolSize = it }

    if (newSettings.isSet("serverPort")) lantaiSettingsProvider.serverPort = newSettings.serverPort
    if (newSettings.isSet("serverContextPath")) lantaiSettingsProvider.serverContextPath = newSettings.serverContextPath

    newSettings.koboProxy?.let { lantaiSettingsProvider.koboProxy = it }
    if (newSettings.isSet("koboPort")) lantaiSettingsProvider.koboPort = newSettings.koboPort
    if (newSettings.isSet("kepubifyPath")) lantaiSettingsProvider.kepubifyPath = newSettings.kepubifyPath
  }
}
