package org.lantai.infrastructure.plugin

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.lantai.application.tasks.Task
import org.lantai.application.tasks.TasksRepository
import org.lantai.domain.model.PluginGrant
import org.lantai.domain.model.PluginInstance
import org.lantai.domain.model.PluginMode
import org.lantai.domain.model.PluginState
import org.lantai.domain.model.PluginTaskSpec
import org.lantai.domain.persistence.PluginStore
import org.lantai.infrastructure.jooq.tasks.TASK_HISTORY_STATUS_DONE
import org.lantai.infrastructure.jooq.tasks.TaskHistoryRow
import org.lantai.infrastructure.jooq.tasks.TasksHistoryDao
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.net.InetSocketAddress
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

@SpringBootTest
@ActiveProfiles("localdb")
class PluginP2bVerificationTest(
  @Autowired private val store: PluginStore,
  @Autowired private val runner: PluginJobRunner,
  @Autowired private val httpClient: PluginHttpClient,
  @Autowired private val scheduler: PluginTaskScheduler,
  @Autowired private val tasksRepository: TasksRepository,
  @Autowired private val history: TasksHistoryDao,
  @Autowired private val mapper: ObjectMapper,
) {
  @AfterEach
  fun tidy() {
    listOf(PLUGIN_ID, SCHEDULE_PLUGIN_ID).forEach { pluginId ->
      store.findByPluginIdOrNull(pluginId)?.let { store.deleteInstance(it.id) }
      store.deleteGrantsByPluginId(pluginId)
    }
    tasksRepository.findAll().filterIsInstance<Task.PluginJob>().forEach { tasksRepository.delete(it.uniqueId) }
  }

  @Test
  fun manifestTaskDeclaration() {
    val manifest =
      """
      {"tasks":[
        {"name":"nightly-organize","cron":"0 30 3 * * *","endpoint":"/v1/tasks/organize","timeoutSeconds":600},
        {"name":"hourly","cron":"0 0 * * *","endpoint":"/v1/tasks/hourly"},
        {"name":"bad-cron","cron":"a b c d e f","endpoint":"/v1/tasks/bad"},
        {"name":"short-cron","cron":"0 0 * *","endpoint":"/v1/tasks/short-cron"},
        {"name":"nightly-organize","cron":"0 0 * * *","endpoint":"/v1/tasks/dup"},
        {"name":"relative","cron":"0 0 * * *","endpoint":"v1/tasks/relative"},
        {"name":"escape","cron":"0 0 * * *","endpoint":"/v1/../tasks/escape"},
        {"name":"absolute-url","cron":"0 0 * * *","endpoint":"http://example.com/v1/tasks"},
        {"name":"short-timeout","cron":"0 0 * * *","endpoint":"/v1/tasks/short","timeoutSeconds":0},
        {"name":"long-timeout","cron":"0 0 * * *","endpoint":"/v1/tasks/long","timeoutSeconds":99999}
      ]}
      """.trimIndent()
    val specs = PluginTaskSpecs.parse(manifest)
    assertEquals(listOf("nightly-organize", "hourly", "short-timeout", "long-timeout"), specs.map { it.name })
    assertEquals(600, specs[0].timeoutSeconds)
    assertEquals(PluginTaskSpecs.DEFAULT_TIMEOUT_SECONDS, specs[1].timeoutSeconds)
    assertEquals(PluginTaskSpecs.MIN_TIMEOUT_SECONDS, specs[2].timeoutSeconds)
    assertEquals(PluginTaskSpecs.MAX_TIMEOUT_SECONDS, specs[3].timeoutSeconds)

    assertTrue(PluginTaskSpecs.parse("{}").isEmpty())
    assertTrue(PluginTaskSpecs.parse("not json").isEmpty())
    assertTrue(PluginTaskSpecs.parse("""{"tasks":[]}""").isEmpty())

    val many =
      (1..12).joinToString(",", prefix = """{"tasks":[""", postfix = "]}") {
        """{"name":"job$it","cron":"0 0 * * *","endpoint":"/v1/tasks/$it"}"""
      }
    assertEquals(PluginTaskSpecs.MAX_TASKS, PluginTaskSpecs.parse(many).size)
  }

  @Test
  fun cronDueWindow() {
    val zone = ZoneId.of("UTC")
    val everyMinute = PluginTaskSpecs.cronOf(PluginTaskSpec("m", "0 * * * * *", "/v1/tasks/m", 5))!!
    assertTrue(PluginTaskSpecs.dueBetween(everyMinute, Instant.parse("2026-11-11T10:00:30Z"), Instant.parse("2026-11-11T10:01:30Z"), zone))
    assertFalse(PluginTaskSpecs.dueBetween(everyMinute, Instant.parse("2026-11-11T10:00:30Z"), Instant.parse("2026-11-11T10:00:45Z"), zone))
    assertFalse(PluginTaskSpecs.dueBetween(everyMinute, Instant.parse("2026-11-11T10:00:30Z"), Instant.parse("2026-11-11T10:00:30Z"), zone))

    val daily = PluginTaskSpecs.cronOf(PluginTaskSpec("d", "0 30 3 * * *", "/v1/tasks/d", 5))!!
    assertFalse(PluginTaskSpecs.dueBetween(daily, Instant.parse("2026-11-10T03:31:00Z"), Instant.parse("2026-11-11T03:29:00Z"), zone))
    assertTrue(PluginTaskSpecs.dueBetween(daily, Instant.parse("2026-11-10T03:31:00Z"), Instant.parse("2026-11-11T03:31:00Z"), zone))
  }

  @Test
  fun pluginJobSerialization() {
    val job = Task.PluginJob(pluginId = PLUGIN_ID, taskName = "nightly", endpoint = "/v1/tasks/nightly", timeoutSeconds = 120)
    assertEquals("PLUGIN_JOB_${PLUGIN_ID}_nightly", job.uniqueId)
    assertEquals("plugin:$PLUGIN_ID", job.source)

    val payload = mapper.writeValueAsString(job)
    val restored = mapper.readValue(payload, Class.forName(job.javaClass.typeName)) as Task
    assertTrue(restored is Task.PluginJob)
    assertEquals(job.uniqueId, restored.uniqueId)
    assertEquals("plugin:$PLUGIN_ID", restored.source)
    assertEquals("/v1/tasks/nightly", (restored as Task.PluginJob).endpoint)
    assertEquals(120, restored.timeoutSeconds)
  }

  @Test
  fun jobRunnerHttpOutcome() {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/v1/tasks/ok") { exchange ->
      exchange.requestBody.readAllBytes()
      exchange.sendResponseHeaders(200, -1)
      exchange.close()
    }
    server.createContext("/v1/tasks/fail") { exchange ->
      exchange.requestBody.readAllBytes()
      exchange.sendResponseHeaders(500, -1)
      exchange.close()
    }
    server.start()
    val port = server.address.port
    try {
      store.insertInstance(
        PluginInstance(
          pluginId = PLUGIN_ID,
          name = "p2b",
          version = "1.0.0",
          apiVersion = 2,
          mode = PluginMode.EXTERNAL,
          runtimeKind = "binary",
          entry = "",
          homeDir = "",
          endpoint = "http://127.0.0.1:$port",
          hostKey = "test-host-key",
          state = PluginState.READY,
        ),
      )

      runner.run(Task.PluginJob(PLUGIN_ID, "ok", "/v1/tasks/ok", 5))

      val failure = assertThrows(PluginJobException::class.java) { runner.run(Task.PluginJob(PLUGIN_ID, "fail", "/v1/tasks/fail", 5)) }
      assertTrue(failure.message.orEmpty().contains("500"))

      assertEquals("任务地址不得跨主机", httpClient.invokeJob("http://127.0.0.1:$port", "//example.com/v1/tasks/ok", null, 5))
      assertThrows(PluginNotFoundException::class.java) { runner.run(Task.PluginJob("test.p2b-missing", "ok", "/v1/tasks/ok", 5)) }
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun historySourceFilter() {
    history.clear()
    val source = "plugin:$PLUGIN_ID"
    val now = LocalDateTime.now(ZoneId.of("Z"))
    history.save(TaskHistoryRow(UUID.randomUUID().toString(), "PluginJob", 2, null, TASK_HISTORY_STATUS_DONE, now, now, 5, null, source))
    history.save(TaskHistoryRow(UUID.randomUUID().toString(), "ScanLibrary", 4, null, TASK_HISTORY_STATUS_DONE, now, now, 5, null, null))

    val scoped = history.list(0, 10, source = source)
    assertEquals(1, scoped.size)
    assertEquals(source, scoped.first().source)
    assertEquals(1, history.count(source = source))
    assertEquals(2, history.count())
    history.clear()
  }

  @Test
  fun schedulerRequiresGrantAndCron() {
    val pluginId = SCHEDULE_PLUGIN_ID
    store.insertInstance(
      PluginInstance(
        pluginId = pluginId,
        name = "p2b-schedule",
        version = "1.0.0",
        apiVersion = 2,
        mode = PluginMode.EXTERNAL,
        runtimeKind = "binary",
        entry = "",
        homeDir = "",
        endpoint = "http://127.0.0.1:1",
        hostKey = "k",
        state = PluginState.READY,
        manifest = """{"tasks":[{"name":"tick","cron":"* * * * * *","endpoint":"/v1/tasks/tick"}]}""",
      ),
    )
    val uniqueId = "PLUGIN_JOB_${pluginId}_tick"
    try {
      val t0 = Instant.now()
      scheduler.scanDue(t0)
      scheduler.scanDue(t0.plusSeconds(2))
      assertEquals(0, tasksRepository.findAll().count { it.uniqueId == uniqueId })

      store.replaceGrants(pluginId, listOf(PluginGrant(pluginId = pluginId, capability = PluginTaskScheduler.CAPABILITY)))
      val t1 = Instant.now().plusSeconds(3)
      scheduler.scanDue(t1)
      scheduler.scanDue(t1.plusSeconds(2))
      assertEquals(1, tasksRepository.findAll().count { it.uniqueId == uniqueId })

      val job = tasksRepository.findAll().first { it.uniqueId == uniqueId } as Task.PluginJob
      assertEquals("/v1/tasks/tick", job.endpoint)
      assertEquals(PluginTaskSpecs.DEFAULT_TIMEOUT_SECONDS, job.timeoutSeconds)
      assertEquals("plugin:$pluginId", job.source)
    } finally {
      tasksRepository.delete(uniqueId)
    }
  }

  companion object {
    private const val PLUGIN_ID = "test.p2b"
    private const val SCHEDULE_PLUGIN_ID = "test.p2b-schedule"
  }
}
