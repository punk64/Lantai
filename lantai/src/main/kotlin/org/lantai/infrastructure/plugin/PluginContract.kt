package org.lantai.infrastructure.plugin

import com.fasterxml.jackson.databind.JsonNode
import org.lantai.domain.model.PluginCapabilities
import org.springframework.scheduling.support.CronExpression

data class PluginContractIssue(
  val code: String,
  val message: String,
)

data class PluginCapabilitySpec(
  val name: String,
  val kind: String,
  val summary: String,
)

data class PluginContractRule(
  val id: String,
  val text: String,
)

data class PluginLimitSpec(
  val name: String,
  val value: String,
)

data class PluginGuideDto(
  val protocol: Int,
  val minHost: String,
  val capabilities: List<PluginCapabilitySpec>,
  val rules: List<PluginContractRule>,
  val checks: List<String>,
  val limits: List<PluginLimitSpec>,
  val sample: String,
)

object PluginContract {
  const val PROTOCOL: Int = 2
  const val MIN_HOST: String = "0.6.23"
  const val MAX_MANIFEST_BYTES: Long = 256L * 1024
  const val MAX_ENTRIES: Int = 4096
  const val MAX_PACKAGE_BYTES: Long = 2L * 1024 * 1024 * 1024
  const val MAX_ENTRY_BYTES: Long = 2L * 1024 * 1024 * 1024
  const val MAX_TOTAL_BYTES: Long = 4L * 1024 * 1024 * 1024
  const val MIN_MEMORY_MB: Int = 32
  const val MAX_MEMORY_MB: Int = 2048
  const val MAX_DESCRIPTION: Int = 2048
  const val MAX_NAME: Int = 255
  const val MAX_ENTRY_PATH: Int = 256
  const val MAX_ID: Int = 128
  const val MAX_VERSION: Int = 64
  const val MAX_SCOPES: Int = 16
  const val MAX_SCOPE_LENGTH: Int = 64
  const val MAX_TASKS: Int = PluginTaskSpecs.MAX_TASKS
  const val MAX_PANELS: Int = PluginPanelSpecs.MAX_PANELS

  private val ID_PATTERN = Regex("^[a-z0-9]+([._-][a-z0-9]+)+$")
  private val VERSION_PATTERN = Regex("^[0-9A-Za-z][0-9A-Za-z._-]*$")
  private val HOST_PATTERN = Regex("^\\d+(\\.\\d+){0,3}$")
  private val TASK_NAME_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$")
  private val PANEL_ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$")
  private val UI_ACTIONS = setOf("toggle", "panel", "upgrade", "refresh")
  private val UI_BADGES = setOf("engine")
  private val CONFIG_TYPES = setOf("string", "number", "integer", "boolean")
  private val RUNTIMES = setOf("jvm", "command")
  private val FORBIDDEN_TOP_DIRS = setOf("data", "logs")

  fun validate(
    node: JsonNode,
    fileExists: ((String) -> Boolean)? = null,
  ): List<PluginContractIssue> {
    if (!node.isObject) return listOf(PluginContractIssue("manifest.shape", "清单必须是 JSON 对象"))
    val issues = mutableListOf<PluginContractIssue>()

    val id = node.path("id").asText("").trim()
    when {
      id.isEmpty() -> issues += PluginContractIssue("id.missing", "缺少 id")
      id.length > MAX_ID -> issues += PluginContractIssue("id.length", "id 超过 $MAX_ID 字符")
      !ID_PATTERN.matches(id) -> issues += PluginContractIssue("id.pattern", "id 不合法：$id（应为小写字母数字，用 . _ - 分隔）")
    }

    val name = node.path("name").asText("").trim()
    when {
      name.isEmpty() -> issues += PluginContractIssue("name.missing", "缺少 name")
      name.length > MAX_NAME -> issues += PluginContractIssue("name.length", "name 超过 $MAX_NAME 字符")
    }

    val version = node.path("version").asText("").trim()
    when {
      version.isEmpty() -> issues += PluginContractIssue("version.missing", "缺少 version")
      version.length > MAX_VERSION -> issues += PluginContractIssue("version.length", "version 超过 $MAX_VERSION 字符")
      !VERSION_PATTERN.matches(version) -> issues += PluginContractIssue("version.pattern", "version 不合法：$version")
    }

    val protocolNode = if (node.has("protocol")) node.path("protocol") else node.path("apiVersion")
    if (protocolNode.isMissingNode || !protocolNode.canConvertToInt()) {
      issues += PluginContractIssue("protocol.missing", "缺少 protocol")
    } else {
      val protocol = protocolNode.asInt()
      if (protocol !in 1..PROTOCOL) issues += PluginContractIssue("protocol.range", "protocol 只接受 1 或 2：$protocol")
    }

    if (node.has("minHost")) {
      val minHost = node.path("minHost").asText("").trim()
      if (minHost.isNotEmpty()) {
        if (minHost.length > 32) {
          issues += PluginContractIssue("minHost.length", "minHost 过长")
        } else if (!HOST_PATTERN.matches(minHost)) {
          issues += PluginContractIssue("minHost.pattern", "minHost 不合法：$minHost（形如 0.6.23）")
        }
      }
    }

    if (node.has("description")) {
      val description = node.path("description").asText("")
      if (description.length > MAX_DESCRIPTION) issues += PluginContractIssue("description.length", "description 超过 $MAX_DESCRIPTION 字符")
    }

    issues += validateCapabilities(node.path("capabilities"))
    issues += validateRuntime(node, fileExists)
    issues += validateTasks(node.path("tasks"))
    issues += validatePanels(node.path("panels"))
    issues += validateUi(node.path("ui"))
    issues += validateConfig(node.path("config"))
    issues += validateScopes(node.path("scopes"))

    return issues
  }

  fun validatePackageLayout(names: Collection<String>): List<PluginContractIssue> {
    val issues = mutableListOf<PluginContractIssue>()
    names.forEach { raw ->
      val name = raw.replace('\\', '/').removePrefix("./").trimStart('/')
      val top = name.substringBefore('/')
      if (top in FORBIDDEN_TOP_DIRS) {
        issues += PluginContractIssue("package.forbidden", "插件包不得携带 $top/ 目录：$raw")
      }
    }
    return issues.distinct()
  }

  fun describe(issues: List<PluginContractIssue>): String =
    "插件不符合开发约定：" + issues.take(6).joinToString("；") { it.message } +
      if (issues.size > 6) "；另有 ${issues.size - 6} 项" else ""

  fun guide(): PluginGuideDto =
    PluginGuideDto(
      protocol = PROTOCOL,
      minHost = MIN_HOST,
      capabilities = CAPABILITIES,
      rules = RULES,
      checks = CHECKS,
      limits = LIMITS,
      sample = SAMPLE,
    )

  private fun validateCapabilities(node: JsonNode): List<PluginContractIssue> {
    if (node.isMissingNode || node.isNull) return emptyList()
    if (!node.isArray && !node.isObject) {
      return listOf(PluginContractIssue("capabilities.shape", "capabilities 必须是数组或对象"))
    }
    val issues = mutableListOf<PluginContractIssue>()
    readCapabilityNames(node).forEach { name ->
      if (!PluginCapabilities.isKnown(name)) {
        issues += PluginContractIssue("capabilities.unknown", "能力不在开放列表内：$name")
      }
    }
    if (node.isObject) {
      node.properties().forEach { (key, value) ->
        if (!value.isTextual) {
          issues += PluginContractIssue("capabilities.path", "能力 $key 的端点必须是字符串")
          return@forEach
        }
        pathIssue(value.asText("").trim())?.let { issues += PluginContractIssue("capabilities.path", "能力 $key 的端点$it") }
      }
    }
    return issues
  }

  private fun validateRuntime(
    node: JsonNode,
    fileExists: ((String) -> Boolean)?,
  ): List<PluginContractIssue> {
    val issues = mutableListOf<PluginContractIssue>()
    val runtime = node.path("runtime").asText("").trim()
    if (runtime.isNotEmpty() && runtime.lowercase() !in RUNTIMES) {
      issues += PluginContractIssue("runtime.value", "runtime 只接受 jvm 或 command：$runtime")
    }
    val entry = node.path("entry").asText("").trim()
    if (entry.isNotEmpty()) {
      when {
        entry.startsWith("/") -> issues += PluginContractIssue("entry.path", "entry 必须是包内相对路径，不能以 / 开头")
        entry.length > MAX_ENTRY_PATH -> issues += PluginContractIssue("entry.path", "entry 超过 $MAX_ENTRY_PATH 字符")
        entry.contains("..") -> issues += PluginContractIssue("entry.path", "entry 不能包含 ..")
        entry.contains("://") -> issues += PluginContractIssue("entry.path", "entry 不能包含 ://")
        entry.contains('\\') -> issues += PluginContractIssue("entry.path", "entry 必须使用 / 分隔")
      }
      if (fileExists != null && !entry.startsWith("/") && fileExists(entry) == false) {
        issues += PluginContractIssue("entry.missing", "entry 指向的文件不在包内：$entry")
      }
    }
    if (node.has("memoryMb")) {
      val memory = node.path("memoryMb").asInt(0)
      if (memory !in MIN_MEMORY_MB..MAX_MEMORY_MB) {
        issues += PluginContractIssue("memoryMb.range", "memoryMb 必须在 $MIN_MEMORY_MB~$MAX_MEMORY_MB 之间：$memory")
      }
    }
    return issues
  }

  private fun validateTasks(node: JsonNode): List<PluginContractIssue> {
    if (node.isMissingNode || node.isNull) return emptyList()
    if (!node.isArray) return listOf(PluginContractIssue("tasks.shape", "tasks 必须是数组"))
    val issues = mutableListOf<PluginContractIssue>()
    if (node.size() > MAX_TASKS) issues += PluginContractIssue("tasks.count", "tasks 最多 $MAX_TASKS 条")
    val seen = mutableSetOf<String>()
    node.forEach { item ->
      val name = item.path("name").asText("").trim()
      if (!TASK_NAME_PATTERN.matches(name)) {
        issues += PluginContractIssue("tasks.name", "任务 name 不合法：$name")
      } else if (!seen.add(name)) {
        issues += PluginContractIssue("tasks.duplicate", "任务 name 重复：$name")
      }
      val cron = item.path("cron").asText("").trim()
      val segments = cron.split(Regex("\\s+")).size
      when {
        cron.isEmpty() -> issues += PluginContractIssue("tasks.cron", "任务 $name 缺少 cron")
        segments !in 5..6 -> issues += PluginContractIssue("tasks.cron", "任务 $name 的 cron 只接受 5 或 6 段")
        else -> {
          val normalized = if (segments == 5) "0 $cron" else cron
          if (runCatching { CronExpression.parse(normalized) }.isFailure) {
            issues += PluginContractIssue("tasks.cron", "任务 $name 的 cron 无法解析：$cron")
          }
        }
      }
      val endpoint = item.path("endpoint").asText("").trim()
      pathIssue(endpoint)?.let { issues += PluginContractIssue("tasks.endpoint", "任务 $name 的 endpoint$it") }
      if (endpoint.length > PluginTaskSpecs.MAX_ENDPOINT_LENGTH) {
        issues += PluginContractIssue("tasks.endpoint", "任务 $name 的 endpoint 超过 ${PluginTaskSpecs.MAX_ENDPOINT_LENGTH} 字符")
      }
      if (item.has("timeoutSeconds")) {
        val timeout = item.path("timeoutSeconds").asInt(PluginTaskSpecs.DEFAULT_TIMEOUT_SECONDS)
        if (timeout !in PluginTaskSpecs.MIN_TIMEOUT_SECONDS..PluginTaskSpecs.MAX_TIMEOUT_SECONDS) {
          issues += PluginContractIssue(
            "tasks.timeout",
            "任务 $name 的 timeoutSeconds 必须在 ${PluginTaskSpecs.MIN_TIMEOUT_SECONDS}~${PluginTaskSpecs.MAX_TIMEOUT_SECONDS} 之间",
          )
        }
      }
    }
    return issues
  }

  private fun validatePanels(node: JsonNode): List<PluginContractIssue> {
    if (node.isMissingNode || node.isNull) return emptyList()
    if (!node.isArray) return listOf(PluginContractIssue("panels.shape", "panels 必须是数组"))
    val issues = mutableListOf<PluginContractIssue>()
    if (node.size() > MAX_PANELS) issues += PluginContractIssue("panels.count", "panels 最多 $MAX_PANELS 条")
    val seen = mutableSetOf<String>()
    node.forEach { item ->
      val id = item.path("id").asText("").trim()
      if (!PANEL_ID_PATTERN.matches(id)) {
        issues += PluginContractIssue("panels.id", "面板 id 不合法：$id")
      } else if (!seen.add(id)) {
        issues += PluginContractIssue("panels.duplicate", "面板 id 重复：$id")
      }
      val title = item.path("title").asText("").trim()
      if (title.isEmpty() || title.length > PluginPanelSpecs.MAX_TEXT_LENGTH) {
        issues += PluginContractIssue("panels.title", "面板 $id 的 title 必须非空且不超过 ${PluginPanelSpecs.MAX_TEXT_LENGTH} 字符")
      }
      val path = item.path("path").asText("/").trim()
      pathIssue(path)?.let { issues += PluginContractIssue("panels.path", "面板 $id 的 path$it") }
      if (path.length > PluginPanelSpecs.MAX_PATH_LENGTH) {
        issues += PluginContractIssue("panels.path", "面板 $id 的 path 超过 ${PluginPanelSpecs.MAX_PATH_LENGTH} 字符")
      }
      if (item.has("roles")) {
        val roles = item.path("roles")
        if (!roles.isArray) {
          issues += PluginContractIssue("panels.roles", "面板 $id 的 roles 必须是数组")
        } else {
          roles.forEach { role ->
            val value = role.asText("").trim()
            if (value.isEmpty() || value != value.uppercase() || value.length > 32) {
              issues += PluginContractIssue("panels.roles", "面板 $id 的角色名必须是大写：$value")
            }
          }
        }
      }
    }
    return issues
  }

  private fun validateUi(node: JsonNode): List<PluginContractIssue> {
    if (node.isMissingNode || node.isNull) return emptyList()
    if (!node.isObject) return listOf(PluginContractIssue("ui.shape", "ui 必须是对象"))
    val issues = mutableListOf<PluginContractIssue>()
    val card = node.path("card")
    if (!card.isMissingNode && !card.isNull) {
      if (!card.isObject) {
        issues += PluginContractIssue("ui.card", "ui.card 必须是对象")
      } else {
        val actions = card.path("actions")
        if (!actions.isMissingNode && !actions.isNull) {
          if (!actions.isArray) {
            issues += PluginContractIssue("ui.actions", "ui.card.actions 必须是数组")
          } else {
            actions.forEach { action ->
              val value = action.asText("").trim()
              if (value !in UI_ACTIONS) issues += PluginContractIssue("ui.actions", "ui.card.actions 只接受 toggle/panel/upgrade/refresh：$value")
            }
          }
        }
        val badges = card.path("badges")
        if (!badges.isMissingNode && !badges.isNull) {
          if (!badges.isArray) {
            issues += PluginContractIssue("ui.badges", "ui.card.badges 必须是数组")
          } else {
            badges.forEach { badge ->
              val value = badge.asText("").trim()
              if (value !in UI_BADGES) issues += PluginContractIssue("ui.badges", "ui.card.badges 只接受 engine：$value")
            }
          }
        }
      }
    }
    return issues
  }

  private fun validateConfig(node: JsonNode): List<PluginContractIssue> {
    if (node.isMissingNode || node.isNull) return emptyList()
    if (!node.isObject) return listOf(PluginContractIssue("config.shape", "config 必须是对象"))
    val issues = mutableListOf<PluginContractIssue>()
    val type = node.path("type").asText("").trim()
    if (type.isNotEmpty() && type != "object") issues += PluginContractIssue("config.type", "config.type 只接受 object：$type")
    val properties = node.path("properties")
    if (!properties.isMissingNode && !properties.isNull && !properties.isObject) {
      issues += PluginContractIssue("config.properties", "config.properties 必须是对象")
    }
    if (properties.isObject) {
      properties.properties().forEach { (key, value) ->
        if (!value.isObject) {
          issues += PluginContractIssue("config.field", "配置项 $key 必须是对象")
          return@forEach
        }
        val fieldType = value.path("type").asText("").trim()
        val hasEnum = value.path("enum").isArray
        if (fieldType.isEmpty() && !hasEnum) {
          issues += PluginContractIssue("config.field", "配置项 $key 缺少 type")
        } else if (fieldType.isNotEmpty() && fieldType !in CONFIG_TYPES) {
          issues += PluginContractIssue("config.field", "配置项 $key 的 type 不支持：$fieldType")
        }
      }
    }
    return issues
  }

  private fun validateScopes(node: JsonNode): List<PluginContractIssue> {
    if (node.isMissingNode || node.isNull) return emptyList()
    if (!node.isArray) return listOf(PluginContractIssue("scopes.shape", "scopes 必须是字符串数组"))
    val issues = mutableListOf<PluginContractIssue>()
    if (node.size() > MAX_SCOPES) issues += PluginContractIssue("scopes.count", "scopes 最多 $MAX_SCOPES 条")
    node.forEach { scope ->
      val value = scope.asText("").trim()
      if (value.isEmpty() || value.length > MAX_SCOPE_LENGTH) {
        issues += PluginContractIssue("scopes.value", "scopes 每项必须非空且不超过 $MAX_SCOPE_LENGTH 字符")
      }
    }
    return issues
  }

  private fun pathIssue(raw: String): String? =
    when {
      raw.isEmpty() -> "不能为空"
      raw.startsWith("//") -> "不能以 // 开头"
      !raw.startsWith("/") -> "必须以 / 开头"
      raw.contains("..") -> "不能包含 .."
      raw.contains("://") -> "不能包含 ://"
      else -> null
    }

  private val CAPABILITIES: List<PluginCapabilitySpec> =
    listOf(
      PluginCapabilitySpec("storage.blob", "消费", "私有键值存储，单值 1 MB、单插件累计 32 MB"),
      PluginCapabilitySpec("event.observe", "消费", "订阅书库、系列、书籍、合集、书单、进度的域事件，最多 2 路并发"),
      PluginCapabilitySpec("credential.store", "消费", "由宿主加密代存凭据，单值 4096 字节，列表永不返回值"),
      PluginCapabilitySpec("task.schedule", "消费", "manifest 声明 cron 任务，宿主动拉起进程后回呼"),
      PluginCapabilitySpec("library.read", "消费", "读取书库、系列、书籍、合集、书单，不带额外角色"),
      PluginCapabilitySpec("metadata.write", "消费", "写入书籍元数据，附带 ADMIN 角色，只能改元数据"),
      PluginCapabilitySpec("progress.write", "消费", "写入阅读进度，不带额外角色"),
      PluginCapabilitySpec("library.write", "消费", "改书库配置、把书籍移入回收站，不提供硬删除"),
      PluginCapabilitySpec("library.import", "消费", "导入书籍文件与转换产物"),
      PluginCapabilitySpec("tts.speak", "提供", "宿主调用插件合成语音，30 秒超时、8 MB 上限"),
      PluginCapabilitySpec("notify.send", "提供", "宿主调用插件发送通知，10 秒超时、64 KB 上限"),
      PluginCapabilitySpec("metadata.provide", "提供", "进入刮削候选池，20 秒超时、1 MB 上限"),
      PluginCapabilitySpec("font.provide", "提供", "提供阅读字体，单文件 8 MB、合计 32 MB"),
      PluginCapabilitySpec("page.transform", "提供", "页图下发前经插件处理，15 秒超时、16 MB 上限"),
      PluginCapabilitySpec("text.transform", "提供", "章节正文下发前经插件处理，15 秒超时、2 MB 上限"),
      PluginCapabilitySpec("search.extend", "提供", "全库搜索零结果时求扩展词，10 秒超时、最多 3 个"),
      PluginCapabilitySpec("convert.provide", "提供", "用户显式触发格式转换，120 秒超时、64 MB 上限"),
      PluginCapabilitySpec("source.provide", "提供", "提供在线源，走 /v1/source/** 固定端点；源用 contentType 声明纯图片、纯文字或图文混排"),
      PluginCapabilitySpec("ui.panel", "提供", "在 manifest 的 panels 里声明导航入口，宿主同源注入 iframe；默认不授权，需在插件详情里手动开启"),
    )

  private val RULES: List<PluginContractRule> =
    listOf(
      PluginContractRule("process", "受管插件只能写 LANTAI_PLUGIN_DATA，不得读写宿主配置目录、数据库文件或其他插件目录。"),
      PluginContractRule("network", "插件必须监听 127.0.0.1，端口由宿主通过 LANTAI_PLUGIN_PORT 下发；宿主逐跳校验地址，禁止重定向到非 loopback。"),
      PluginContractRule("auth", "插件必须校验 X-Lantai-Host-Key，只接受宿主调用；回调宿主只带 X-API-Token，不得把令牌或凭据写日志、写回显。"),
      PluginContractRule("capability", "插件只能访问已授权能力对应的宿主端点；面板页与宿主同源，不得越权读写宿主页面数据或冒用登录态调用宿主 REST。"),
      PluginContractRule("data", "持久化数据只放 LANTAI_PLUGIN_DATA；插件包内不得携带 data/ 与 logs/ 目录，不得假设宿主保留临时文件。"),
      PluginContractRule("runtime", "宿主只保证有 JRE。脚本形态必须自行确认目标镜像有对应解释器，否则要落到 READY 之后才能用。"),
      PluginContractRule("degrade", "提供型能力失败必须降级为空结果或原文，不得让宿主抛错；超时与响应体积由宿主强制截断。"),
      PluginContractRule("lifecycle", "同一版本不可覆盖安装，升级必须换 version；升级失败宿主整组回滚并重新拉起旧版本。"),
      PluginContractRule("uninstall", "卸载会删除版本目录；data 目录按未安装时的选择保留或清理，插件不得把不可重建的数据放在版本目录内。"),
      PluginContractRule("manifest", "manifest 声明即契约：不得声明未开放的能力，不得在运行时改变 id 与 version，声明与实际行为必须一致。"),
    )

  private val CHECKS: List<String> =
    listOf(
      "plugin.json 位于包根、是合法 JSON，且不超过 256 KB",
      "id 匹配 ^[a-z0-9]+([._-][a-z0-9]+)+$ 且不超过 128 字符",
      "name 非空且不超过 255 字符，version 匹配 ^[0-9A-Za-z][0-9A-Za-z._-]*$",
      "protocol 只接受 1 或 2，minHost 形如 0.6.23，description 不超过 2048 字符",
      "capabilities 声明的每个名字必须在开放能力列表内，端点路径必须以 / 开头且不含 //、..、://",
      "runtime 只接受 jvm 或 command，entry 必须是包内已存在的相对路径",
      "memoryMb 必须在 32~2048 之间",
      "tasks 最多 8 条，cron 只接受 5 或 6 段且能解析，endpoint 以 / 开头且不超过 256 字符",
      "panels 最多 8 条，id 与 title 必填，path 以 / 开头且不超过 128 字符，roles 必须是大写角色名",
      "ui.card.actions 只接受 toggle/panel/upgrade/refresh，badges 只接受 engine",
      "config.properties 每项必须有 type，且只接受 string/number/integer/boolean",
      "包内条目不超过 4096，单文件不超过 2 GB，解压后合计不超过 4 GB，插件包本身不超过 2 GB",
      "包内路径必须落在包根内，不得出现 data/ 与 logs/ 目录",
      "运行时 /v1/manifest 返回的清单按同一套规则复核，不合规的插件装不上也起不来",
    )

  private val LIMITS: List<PluginLimitSpec> =
    listOf(
      PluginLimitSpec("KV 单值", "1 MB"),
      PluginLimitSpec("KV 累计", "32 MB"),
      PluginLimitSpec("凭据单值", "4096 字节"),
      PluginLimitSpec("定时任务", "8 条，单次 5~3600 秒"),
      PluginLimitSpec("面板入口", "8 条"),
      PluginLimitSpec("插件端口", "26000~26999"),
      PluginLimitSpec("提供型超时", "10~120 秒，按能力定"),
      PluginLimitSpec("面板代理", "30 秒 / 8 MB"),
      PluginLimitSpec("事件订阅", "2 路并发，缓冲 512 条"),
      PluginLimitSpec("清单体积", "256 KB"),
      PluginLimitSpec("在线正文", "2 MB / 30 秒"),
      PluginLimitSpec("在线内嵌图片", "16 MB / 20 秒"),
    )

  private val SAMPLE: String =
    """
{
  "id": "acme.digest",
  "name": "每日摘要",
  "version": "1.0.0",
  "protocol": 2,
  "minHost": "0.6.23",
  "runtime": "jvm",
  "entry": "bin/plugin.jar",
  "memoryMb": 128,
  "description": "把宿主通知写入私有存储，并按 cron 汇总。",
  "capabilities": ["notify.send", "storage.blob", "task.schedule"],
  "tasks": [
    {"name": "dailyDigest", "cron": "0 9 * * *", "endpoint": "/v1/tasks/digest", "timeoutSeconds": 120}
  ],
  "ui": {"card": {"actions": ["toggle", "upgrade"], "badges": []}}
}
    """.trimIndent()
}
