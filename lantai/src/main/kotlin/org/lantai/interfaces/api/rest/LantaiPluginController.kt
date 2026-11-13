package org.lantai.interfaces.api.rest

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import jakarta.servlet.http.HttpServletRequest
import org.lantai.domain.model.PluginGrant
import org.lantai.domain.model.PluginInstance
import org.lantai.domain.model.PluginState
import org.lantai.infrastructure.plugin.PluginCallLog
import org.lantai.infrastructure.plugin.PluginCallRecord
import org.lantai.infrastructure.plugin.PluginConflictException
import org.lantai.infrastructure.plugin.PluginContract
import org.lantai.infrastructure.plugin.PluginCredentialService
import org.lantai.infrastructure.plugin.PluginCredentialSummary
import org.lantai.infrastructure.plugin.PluginGuideDto
import org.lantai.infrastructure.plugin.PluginHttpClient
import org.lantai.infrastructure.plugin.PluginInstallJob
import org.lantai.infrastructure.plugin.PluginInstallService
import org.lantai.infrastructure.plugin.PluginNotFoundException
import org.lantai.infrastructure.plugin.PluginIndexEntry
import org.lantai.infrastructure.plugin.PluginInstanceService
import org.lantai.infrastructure.plugin.PluginKvService
import org.lantai.infrastructure.plugin.PluginRepositoryClient
import org.lantai.infrastructure.plugin.PluginRequestException
import org.lantai.infrastructure.plugin.PluginSample
import org.lantai.infrastructure.plugin.PluginSamplePackages
import org.lantai.infrastructure.plugin.PluginPanelSpec
import org.lantai.infrastructure.plugin.PluginPanelSpecs
import org.lantai.infrastructure.plugin.PluginPanelTicketService
import org.lantai.infrastructure.plugin.PluginTaskSpecs
import org.lantai.infrastructure.online.OnlineSourceIds
import org.lantai.infrastructure.online.OnlineSourceRegistry
import org.lantai.infrastructure.online.OnlineSyncService
import org.lantai.infrastructure.security.LantaiPrincipal
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Files
import java.time.Duration
import java.time.LocalDateTime

@RestController
@RequestMapping("api/lantai/v1/plugins", produces = [MediaType.APPLICATION_JSON_VALUE])
@PreAuthorize("hasRole('ADMIN')")
class LantaiPluginController(
  private val instances: PluginInstanceService,
  private val repositories: PluginRepositoryClient,
  private val credentials: PluginCredentialService,
  private val callLog: PluginCallLog,
  private val kv: PluginKvService,
  private val samplePackages: PluginSamplePackages,
  private val httpClient: PluginHttpClient,
  private val installService: PluginInstallService,
  private val onlineSync: OnlineSyncService,
  private val onlineRegistry: OnlineSourceRegistry,
  private val tickets: PluginPanelTicketService,
) {
  private val mapper = ObjectMapper()

  @GetMapping
  @Operation(summary = "List plugin instances")
  fun list(): List<PluginDto> = instances.list().map { it.toDto() }

  @GetMapping("status")
  @Operation(summary = "Read live plugin states for polling")
  fun status(): List<PluginStatusDto> =
    instances.list().map {
      PluginStatusDto(
        id = it.id,
        pluginId = it.pluginId,
        state = it.state.name,
        enabled = it.enabled,
        mode = it.mode.name,
        pid = it.pid,
        endpoint = it.endpoint,
        statusMessage = it.statusMessage,
        lastHealthDate = it.lastHealthDate,
      )
    }

  @GetMapping("guide")
  @Operation(summary = "Read the open capability list and the plugin development contract")
  fun guide(): PluginGuideDto = PluginContract.guide()

  @GetMapping("panels")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "List panel entries declared by authorized plugins")
  fun panels(
    @AuthenticationPrincipal principal: LantaiPrincipal?,
    request: HttpServletRequest,
  ): List<PluginPanelDto> = translate { visiblePanels(principal, request) }

  private fun visiblePanels(
    principal: LantaiPrincipal?,
    request: HttpServletRequest,
  ): List<PluginPanelDto> {
    val roles = principal?.user?.roles?.map { it.name }?.toSet() ?: emptySet()
    val sessionId = request.getSession(false)?.id ?: return emptyList()
    return instances.list().flatMap { instance ->
      if (!instance.enabled || instance.state != PluginState.READY) return@flatMap emptyList()
      if (!instance.capabilityList().contains("ui.panel")) return@flatMap emptyList()
      if (instances.grants(instance.id).none { it.capability == "ui.panel" }) return@flatMap emptyList()
      PluginPanelSpecs.parse(instance.manifest)
        .filter { it.roles.isEmpty() || it.roles.any { role -> role in roles } }
        .map { spec ->
          val separator = if (spec.path.contains("?")) "&" else "?"
          val ticket = tickets.issue(instance.id, spec.id, sessionId)
          PluginPanelDto(
            instanceId = instance.id,
            pluginId = instance.pluginId,
            panelId = spec.id,
            title = spec.title,
            icon = spec.icon,
            group = spec.group,
            titleI18n = spec.titleI18n,
            roles = spec.roles,
            url = "/api/lantai/v1/plugins/${instance.id}/panel${spec.path}${separator}panel=${spec.id}&t=$ticket",
          )
        }
    }
  }

  private fun panelAccessible(
    principal: LantaiPrincipal?,
    instance: PluginInstance,
    specs: List<PluginPanelSpec>,
  ): Boolean {
    if (!instance.enabled || instance.state != PluginState.READY) return false
    if (!instance.capabilityList().contains("ui.panel")) return false
    if (instances.grants(instance.id).none { it.capability == "ui.panel" }) return false
    val roles = principal?.user?.roles?.map { it.name }?.toSet() ?: emptySet()
    return specs.any { it.roles.isEmpty() || it.roles.any { role -> role in roles } }
  }

  private fun panelCsp(): String =
    "default-src 'self'; script-src 'self' 'unsafe-inline' 'unsafe-eval'; " +
      "style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self' data:; " +
      "media-src 'self' blob:; connect-src 'self'; object-src 'none'; base-uri 'none'; " +
      "form-action 'self'; frame-ancestors 'self'"

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Register an external plugin")
  fun register(
    @RequestBody body: PluginRegisterDto,
    @AuthenticationPrincipal principal: LantaiPrincipal?,
  ): PluginDto = translate { instances.register(body.endpoint, body.hostKey, principal?.user?.id.orEmpty()).toDto(revealHostKey = true) }

  @PostMapping("install")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Install a managed plugin from a local zip package")
  fun install(
    @RequestBody body: PluginInstallDto,
    @AuthenticationPrincipal principal: LantaiPrincipal?,
  ): PluginDto = translate { instances.install(body.zipPath, principal?.user?.id.orEmpty()).toDto(revealHostKey = true) }

  @PostMapping("install/upload", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Install a managed plugin from a zip uploaded by the browser")
  fun installUpload(
    @RequestPart("file") file: MultipartFile,
    @AuthenticationPrincipal principal: LantaiPrincipal?,
  ): PluginDto =
    translate {
      val staged = installService.stage(file)
      try {
        instances.install(staged.toString(), principal?.user?.id.orEmpty()).toDto(revealHostKey = true)
      } finally {
        runCatching { Files.deleteIfExists(staged) }
      }
    }

  @PostMapping("install/upload/job", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
  @ResponseStatus(HttpStatus.ACCEPTED)
  @Operation(summary = "Start a background install from an uploaded zip package")
  fun installUploadJob(
    @RequestPart("file") file: MultipartFile,
    @AuthenticationPrincipal principal: LantaiPrincipal?,
  ): PluginJobAcceptedDto = translate { PluginJobAcceptedDto(installService.submitInstall(file, principal?.user?.id.orEmpty())) }

  @GetMapping("install/jobs")
  @Operation(summary = "List running plugin install jobs and those finished in the last few minutes")
  fun installJobs(): List<PluginJobDto> = (installService.activeJobs() + installService.recentJobs()).map { it.toDto() }

  @GetMapping("install/jobs/{jobId}")
  @Operation(summary = "Read one plugin install job")
  fun installJob(
    @PathVariable jobId: String,
  ): PluginJobDto = translate { installService.job(jobId)?.toDto() ?: throw PluginNotFoundException("安装任务不存在") }

  @DeleteMapping("install/jobs/{jobId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Drop a finished plugin install job from the list")
  fun dismissInstallJob(
    @PathVariable jobId: String,
  ) {
    installService.dismiss(jobId)
  }

  @GetMapping("samples")
  @Operation(summary = "List plugin packages bundled with the host")
  fun sampleList(): List<PluginSampleDto> = samplePackages.list().map { it.toDto() }

  @PostMapping("samples/{sampleId}/install")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Install a bundled sample plugin")
  fun installSample(
    @PathVariable sampleId: String,
    @AuthenticationPrincipal principal: LantaiPrincipal?,
  ): PluginDto =
    translate {
      val staged = samplePackages.stage(sampleId)
      try {
        instances.install(staged.toString(), principal?.user?.id.orEmpty()).toDto(revealHostKey = true)
      } finally {
        runCatching { Files.deleteIfExists(staged) }
      }
    }

  @PostMapping("repository/index")
  @Operation(summary = "Fetch and validate a plugin repository index through the host proxy")
  fun index(
    @RequestBody body: PluginIndexRequestDto,
  ): PluginIndexDto =
    translate {
      val index = repositories.fetchIndex(body.indexUrl)
      PluginIndexDto(source = index.source, entries = index.entries.map { it.toDto() })
    }

  @PostMapping("repository/install")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Install a managed plugin listed in a repository index")
  fun installFromRepository(
    @RequestBody body: PluginRepositoryInstallDto,
    @AuthenticationPrincipal principal: LantaiPrincipal?,
  ): PluginDto =
    translate {
      val index = repositories.fetchIndex(body.indexUrl)
      val entry =
        index.entries.firstOrNull { it.pluginId == body.pluginId && (body.version.isNullOrBlank() || it.version == body.version) }
          ?: throw PluginNotFoundException("索引中没有插件 ${body.pluginId} ${body.version.orEmpty()}".trim())
      instances.installFromIndex(entry, principal?.user?.id.orEmpty()).toDto(revealHostKey = true)
    }

  @PostMapping("repository/install/job")
  @ResponseStatus(HttpStatus.ACCEPTED)
  @Operation(summary = "Start a background install from a repository index entry")
  fun installFromRepositoryJob(
    @RequestBody body: PluginRepositoryInstallDto,
    @AuthenticationPrincipal principal: LantaiPrincipal?,
  ): PluginJobAcceptedDto =
    translate {
      PluginJobAcceptedDto(
        installService.submitInstallFromRepository(body.indexUrl, body.pluginId, body.version, principal?.user?.id.orEmpty()),
      )
    }

  @PostMapping("{id}/upgrade")
  @Operation(summary = "Upgrade a managed plugin from a local zip package")
  fun upgrade(
    @PathVariable id: String,
    @RequestBody body: PluginUpgradeDto,
  ): PluginDto = translate { instances.upgrade(id, body.zipPath).toDto(revealHostKey = true) }

  @PostMapping("{id}/upgrade/upload", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
  @Operation(summary = "Upgrade a managed plugin from a zip uploaded by the browser")
  fun upgradeUpload(
    @PathVariable id: String,
    @RequestPart("file") file: MultipartFile,
  ): PluginDto =
    translate {
      val staged = installService.stage(file)
      try {
        instances.upgrade(id, staged.toString()).toDto(revealHostKey = true)
      } finally {
        runCatching { Files.deleteIfExists(staged) }
      }
    }

  @PostMapping("{id}/upgrade/repository")
  @Operation(summary = "Upgrade a managed plugin from a repository index entry")
  fun upgradeFromRepository(
    @PathVariable id: String,
    @RequestBody body: PluginRepositoryUpgradeDto,
  ): PluginDto =
    translate {
      val index = repositories.fetchIndex(body.indexUrl)
      val entry =
        index.entries.firstOrNull { it.pluginId == body.pluginId && (body.version.isNullOrBlank() || it.version == body.version) }
          ?: throw PluginNotFoundException("索引中没有插件 ${body.pluginId} ${body.version.orEmpty()}".trim())
      instances.upgradeFromIndex(id, entry).toDto(revealHostKey = true)
    }

  @PostMapping("{id}/upgrade/upload/job", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
  @ResponseStatus(HttpStatus.ACCEPTED)
  @Operation(summary = "Start a background upgrade from an uploaded zip package")
  fun upgradeUploadJob(
    @PathVariable id: String,
    @RequestPart("file") file: MultipartFile,
  ): PluginJobAcceptedDto = translate { PluginJobAcceptedDto(installService.submitUpgrade(id, file)) }

  @PostMapping("{id}/upgrade/repository/job")
  @ResponseStatus(HttpStatus.ACCEPTED)
  @Operation(summary = "Start a background upgrade from a repository index entry")
  fun upgradeFromRepositoryJob(
    @PathVariable id: String,
    @RequestBody body: PluginRepositoryUpgradeDto,
  ): PluginJobAcceptedDto =
    translate {
      PluginJobAcceptedDto(installService.submitUpgradeFromRepository(id, body.indexUrl, body.pluginId, body.version))
    }

  @GetMapping("{id}/logs")
  @Operation(summary = "Read the tail of a managed plugin log")
  fun logs(
    @PathVariable id: String,
    @RequestParam(name = "lines", defaultValue = "200") lines: Int,
  ): PluginLogDto = translate { PluginLogDto(instances.logTail(id, lines)) }

  @PatchMapping("{id}")
  @Operation(summary = "Enable or disable a plugin")
  fun update(
    @PathVariable id: String,
    @RequestBody body: PluginEnabledDto,
  ): PluginDto {
    val updated = translate { instances.setEnabled(id, body.enabled) }
    runCatching { onlineRegistry.refreshVisibility() }
    return updated.toDto()
  }

  @PostMapping("{id}/refresh")
  @Operation(summary = "Re-run the handshake for a plugin")
  fun refresh(
    @PathVariable id: String,
  ): PluginDto = translate { instances.refresh(id).toDto() }

  @PostMapping("{id}/release")
  @Operation(summary = "Stop a managed plugin process to free memory")
  fun release(
    @PathVariable id: String,
  ): PluginDto = translate { instances.release(id).toDto() }

  @GetMapping("{id}/online-count")
  @Operation(summary = "Count online series subscribed through a plugin")
  fun onlineCount(
    @PathVariable id: String,
  ): Map<String, Int> {
    val pluginId = translate { instances.pluginIdOf(id) }
    return mapOf("count" to translate { onlineSync.countBySourceKind(OnlineSourceIds.pluginKind(pluginId)) })
  }

  @DeleteMapping("{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Remove a plugin and its grants")
  fun delete(
    @PathVariable id: String,
    @RequestParam(defaultValue = "false") purgeDownloads: Boolean,
  ) {
    val pluginId = translate { instances.pluginIdOf(id) }
    runCatching { onlineSync.removeBySourceKind(OnlineSourceIds.pluginKind(pluginId), purgeDownloads) }
      .onFailure { logger.warn(it) { "Removing online series of plugin $pluginId failed" } }
    translate { instances.delete(id) }
    runCatching { onlineRegistry.refreshVisibility() }
  }

  @GetMapping("{id}/grants")
  @Operation(summary = "List granted capabilities of a plugin")
  fun grants(
    @PathVariable id: String,
  ): List<PluginGrantDto> = translate { instances.grants(id).map { it.toDto() } }

  @PutMapping("{id}/grants")
  @Operation(summary = "Replace granted capabilities of a plugin")
  fun updateGrants(
    @PathVariable id: String,
    @RequestBody body: PluginGrantUpdateDto,
    @AuthenticationPrincipal principal: LantaiPrincipal,
  ): List<PluginGrantDto> =
    translate {
      instances
        .updateGrants(id, body.capabilities, body.defaultCapability, principal.user.id)
        .map { it.toDto() }
    }

  @PatchMapping("{id}/settings")
  @Operation(summary = "Replace the JSON settings of a plugin and restart managed processes")
  fun updateSettings(
    @PathVariable id: String,
    @RequestBody body: JsonNode,
  ): PluginDto = translate { instances.updateSettings(id, body.toString()).toDto() }

  @GetMapping("{id}/kv")
  @Operation(summary = "Report the key-value usage of a plugin")
  fun kvUsage(
    @PathVariable id: String,
  ): PluginKvUsageDto =
    translate {
      val pluginId = instances.pluginIdOf(id)
      PluginKvUsageDto(
        usage = kv.usage(pluginId),
        limit = PluginKvService.MAX_TOTAL_BYTES,
        count = kv.count(pluginId),
      )
    }

  @GetMapping("{id}/calls")
  @Operation(summary = "List recent capability calls of a plugin, newest first")
  fun calls(
    @PathVariable id: String,
    @RequestParam(name = "limit", defaultValue = "20") limit: Int,
  ): List<PluginCallRecord> = translate { callLog.recent(instances.pluginIdOf(id), limit) }

  @GetMapping("{id}/credentials")
  @Operation(summary = "List credential names stored by a plugin, values excluded")
  fun credentials(
    @PathVariable id: String,
  ): List<PluginCredentialSummary> = translate { credentials.list(instances.pluginIdOf(id)) }

  @DeleteMapping("{id}/credentials/{name}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Delete one credential stored by a plugin")
  fun deleteCredential(
    @PathVariable id: String,
    @PathVariable name: String,
  ) {
    translate { credentials.delete(instances.pluginIdOf(id), name) }
  }

  @RequestMapping("{id}/panel/**", produces = [MediaType.ALL_VALUE])
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "Proxy a declared plugin panel through the host")
  fun panel(
    @PathVariable id: String,
    @AuthenticationPrincipal principal: LantaiPrincipal?,
    request: HttpServletRequest,
  ): ResponseEntity<ByteArray> {
    if (request.getHeader("Sec-Fetch-Dest") == "document") {
      throw ResponseStatusException(HttpStatus.FORBIDDEN, "插件面板只能在宿主内嵌中打开")
    }
    val sessionId =
      request.getSession(false)?.id
        ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "会话已失效")
    val instance = translate { instances.prepareById(id) }
    val specs = PluginPanelSpecs.parse(instance.manifest)
    if (!panelAccessible(principal, instance, specs)) {
      throw ResponseStatusException(HttpStatus.FORBIDDEN, "插件面板未授权")
    }
    val suffix =
      PluginPanelSpecs.sanitizePath(request.requestURI.substringAfter("/panel", "").ifEmpty { "/" }, specs)
        ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "插件面板路径不合法")
    val cookieName = PluginPanelTicketService.COOKIE_PREFIX + id.takeLast(8)
    val ticketValue = request.getParameter("t")
    val cookieValue = request.cookies?.firstOrNull { it.name == cookieName }?.value
    val ticketGrant =
      ticketValue
        ?.takeIf { it.isNotBlank() }
        ?.let { tickets.peek(it, id, sessionId, request.getParameter("panel")) }
    val grant =
      ticketGrant
        ?: cookieValue?.takeIf { it.isNotBlank() }?.let { tickets.verify(it, id, sessionId) }
        ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "插件面板票据无效")
    val endpoint = instance.endpoint?.takeIf { it.isNotBlank() }
      ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "插件没有可用地址")
    val query = request.queryString?.takeIf { it.isNotBlank() }?.let { "?$it" }.orEmpty()
    val target = "/v1/panel$suffix$query"
    val call =
      if (HttpMethod.POST.matches(request.method)) {
        val body = request.inputStream.readBytes()
        httpClient.invokePostRaw(
          endpoint,
          target,
          instance.hostKey,
          body,
          request.contentType ?: MediaType.APPLICATION_JSON_VALUE,
          PANEL_TIMEOUT_SECONDS,
          PANEL_MAX_BYTES,
        )
      } else {
        httpClient.invokeGet(endpoint, target, instance.hostKey, PANEL_TIMEOUT_SECONDS, PANEL_MAX_BYTES)
      }
    if (!call.ok) {
      val status = if (call.status in 400..599) call.status else HttpStatus.BAD_GATEWAY.value()
      val payload = mapper.writeValueAsBytes(mapOf("message" to (call.message ?: "插件面板不可用")))
      return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(payload)
    }
    val media = runCatching { MediaType.parseMediaType(call.contentType ?: MediaType.APPLICATION_JSON_VALUE) }.getOrDefault(MediaType.APPLICATION_JSON)
    var builder =
      ResponseEntity
        .status(call.status)
        .header(HttpHeaders.CONTENT_TYPE, media.toString())
        .header("Content-Security-Policy", panelCsp())
        .header("Referrer-Policy", "no-referrer")
    if (ticketGrant != null) {
      tickets.consume(ticketValue, id, sessionId, null)
      val cookie =
        ResponseCookie
          .from(cookieName, tickets.openSession(grant))
          .path("/api/lantai/v1/plugins/$id/panel/")
          .httpOnly(true)
          .sameSite("Lax")
          .maxAge(Duration.ofHours(8))
          .build()
      builder = builder.header(HttpHeaders.SET_COOKIE, cookie.toString())
    }
    return builder.body(call.body ?: ByteArray(0))
  }

  private companion object {
    private const val PANEL_TIMEOUT_SECONDS = 120
    private const val PANEL_MAX_BYTES = 8 * 1024 * 1024
  }

  private fun <T> translate(block: () -> T): T =
    try {
      block()
    } catch (ex: PluginNotFoundException) {
      throw ResponseStatusException(HttpStatus.NOT_FOUND, ex.message)
    } catch (ex: PluginConflictException) {
      throw ResponseStatusException(HttpStatus.CONFLICT, ex.message)
    } catch (ex: PluginRequestException) {
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, ex.message)
    }

  private fun PluginInstallJob.toDto() =
    PluginJobDto(
      jobId = jobId,
      kind = kind,
      phase = phase,
      pct = pct,
      label = label,
      pluginId = pluginId,
      pluginName = pluginName,
      pluginVersion = pluginVersion,
      error = error,
      finished = finished,
      startedDate = startedDate,
      modifiedDate = modifiedDate,
    )

  private fun PluginSample.toDto() =
    PluginSampleDto(
      id = id,
      pluginId = pluginId,
      name = name,
      version = version,
      description = description,
      sizeBytes = sizeBytes,
    )

  private fun PluginInstance.toDto(revealHostKey: Boolean = false): PluginDto =
    PluginDto(
      id = id,
      pluginId = pluginId,
      name = name,
      version = version,
      apiVersion = apiVersion.takeIf { it > 0 },
      mode = mode.name,
      runtimeKind = runtimeKind,
      entry = entry,
      endpoint = endpoint,
      hostKey = when {
        hostKey.isNullOrBlank() -> null
        revealHostKey -> hostKey
        else -> hostKey.take(6) + "…" + hostKey.takeLast(4)
      },
      state = state.name,
      statusMessage = statusMessage,
      capabilities = capabilityList(),
      tasks = PluginTaskSpecs.parse(manifest).map { PluginTaskDto(it.name, it.cron, it.endpoint, it.timeoutSeconds) },
      settings = settings,
      config = configSchema(),
      ui = uiSpec(),
      memoryMb = memoryMb,
      enabled = enabled,
      pid = pid,
      lastHealthDate = lastHealthDate,
      lastUsedDate = lastUsedDate,
      createdDate = createdDate,
    )

  private fun PluginIndexEntry.toDto(): PluginIndexEntryDto =
    PluginIndexEntryDto(
      pluginId = pluginId,
      name = name,
      version = version,
      protocol = protocol,
      minHost = minHost,
      capabilities = capabilities,
      downloadUrl = downloadUrl,
      sha256 = sha256,
    )

  private fun PluginGrant.toDto(): PluginGrantDto =
    PluginGrantDto(
      capability = capability,
      isDefault = isDefault,
      grantedBy = grantedBy,
      createdDate = createdDate,
    )

  private fun PluginInstance.capabilityList(): List<String> =
    runCatching { mapper.readTree(capabilities).map { it.asText() } }.getOrDefault(emptyList())

  private fun PluginInstance.configSchema(): JsonNode? =
    runCatching { mapper.readTree(manifest).path("config") }.getOrNull()?.takeIf { it.isObject }

  private fun PluginInstance.uiSpec(): JsonNode? =
    runCatching { mapper.readTree(manifest).path("ui") }.getOrNull()?.takeIf { it.isObject }
}

data class PluginPanelDto(
  val instanceId: String,
  val pluginId: String,
  val panelId: String,
  val title: String,
  val icon: String,
  val group: String,
  val titleI18n: Map<String, String>,
  val roles: List<String>,
  val url: String,
)

data class PluginKvUsageDto(
  val usage: Long,
  val limit: Long,
  val count: Int,
)

data class PluginJobAcceptedDto(
  val jobId: String,
)

data class PluginJobDto(
  val jobId: String,
  val kind: String,
  val phase: String,
  val pct: Int,
  val label: String,
  val pluginId: String?,
  val pluginName: String?,
  val pluginVersion: String?,
  val error: String?,
  val finished: Boolean,
  val startedDate: LocalDateTime,
  val modifiedDate: LocalDateTime,
)

private val logger = KotlinLogging.logger {}

data class PluginDto(
  val id: String,
  val pluginId: String,
  val name: String,
  val version: String,
  val apiVersion: Int?,
  val mode: String,
  val runtimeKind: String,
  val entry: String,
  val endpoint: String?,
  val hostKey: String?,
  val state: String,
  val statusMessage: String?,
  val capabilities: List<String>,
  val tasks: List<PluginTaskDto>,
  val settings: String,
  val config: JsonNode?,
  val ui: JsonNode?,
  val memoryMb: Int,
  val enabled: Boolean,
  val pid: Int?,
  val lastHealthDate: LocalDateTime?,
  val lastUsedDate: LocalDateTime?,
  val createdDate: LocalDateTime,
)

data class PluginStatusDto(
  val id: String,
  val pluginId: String,
  val state: String,
  val enabled: Boolean,
  val mode: String,
  val pid: Int?,
  val endpoint: String?,
  val statusMessage: String?,
  val lastHealthDate: LocalDateTime?,
)

data class PluginGrantDto(
  val capability: String,
  val isDefault: Boolean,
  val grantedBy: String?,
  val createdDate: LocalDateTime,
)

data class PluginTaskDto(
  val name: String,
  val cron: String,
  val endpoint: String,
  val timeoutSeconds: Int,
)

data class PluginRegisterDto(
  val endpoint: String = "",
  val hostKey: String? = null,
)

data class PluginInstallDto(
  val zipPath: String = "",
)

data class PluginUpgradeDto(
  val zipPath: String = "",
)

data class PluginRepositoryUpgradeDto(
  val indexUrl: String = "",
  val pluginId: String = "",
  val version: String? = null,
)

data class PluginLogDto(
  val lines: List<String>,
)

data class PluginIndexRequestDto(
  val indexUrl: String = "",
)

data class PluginRepositoryInstallDto(
  val indexUrl: String = "",
  val pluginId: String = "",
  val version: String? = null,
)

data class PluginIndexDto(
  val source: String,
  val entries: List<PluginIndexEntryDto>,
)

data class PluginSampleDto(
  val id: String,
  val pluginId: String,
  val name: String,
  val version: String,
  val description: String,
  val sizeBytes: Long,
)

data class PluginIndexEntryDto(
  val pluginId: String,
  val name: String,
  val version: String,
  val protocol: Int,
  val minHost: String,
  val capabilities: List<String>,
  val downloadUrl: String,
  val sha256: String,
)

data class PluginEnabledDto(
  val enabled: Boolean = false,
)

data class PluginGrantUpdateDto(
  val capabilities: List<String> = emptyList(),
  val defaultCapability: String? = null,
)
