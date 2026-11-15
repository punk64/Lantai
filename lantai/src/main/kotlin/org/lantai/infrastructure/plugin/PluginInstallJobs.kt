package org.lantai.infrastructure.plugin

import com.github.f4b6a3.tsid.TsidCreator
import org.lantai.domain.model.PluginInstance
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

const val PHASE_UPLOAD = "upload"
const val PHASE_DOWNLOAD = "download"
const val PHASE_EXTRACT = "extract"
const val PHASE_REGISTER = "register"
const val PHASE_START = "start"
const val PHASE_HANDSHAKE = "handshake"
const val PHASE_DONE = "done"
const val PHASE_ERROR = "error"

const val STAGE_UPLOAD = "upload"
const val STAGE_DOWNLOAD = "download"
const val STAGE_EXTRACT = "extract"
const val STAGE_INSTALL = "install"

fun stageSlot(phase: String): String? =
  when (phase) {
    PHASE_UPLOAD -> STAGE_UPLOAD
    PHASE_DOWNLOAD -> STAGE_DOWNLOAD
    PHASE_EXTRACT -> STAGE_EXTRACT
    PHASE_REGISTER, PHASE_START, PHASE_HANDSHAKE -> STAGE_INSTALL
    else -> null
  }

fun interface PluginInstallListener {
  fun on(
    phase: String,
    pct: Int,
    label: String,
  )

  companion object {
    val NONE = PluginInstallListener { _, _, _ -> }
  }
}

data class PluginInstallJob(
  val jobId: String,
  val kind: String,
  val phase: String,
  val pct: Int,
  val label: String,
  val pluginId: String? = null,
  val pluginName: String? = null,
  val pluginVersion: String? = null,
  val error: String? = null,
  val stages: Map<String, Int> = emptyMap(),
  val startedDate: LocalDateTime = LocalDateTime.now(),
  val modifiedDate: LocalDateTime = LocalDateTime.now(),
) {
  val finished: Boolean
    get() = phase == PHASE_DONE || phase == PHASE_ERROR
}

@Service
class PluginInstallJobs {
  private val jobs = ConcurrentHashMap<String, PluginInstallJob>()

  fun create(
    kind: String,
    label: String,
  ): String {
    sweep()
    val jobId = TsidCreator.getTsid256().toString()
    jobs[jobId] = PluginInstallJob(jobId = jobId, kind = kind, phase = PHASE_UPLOAD, pct = -1, label = label)
    return jobId
  }

  fun get(jobId: String): PluginInstallJob? = jobs[jobId]

  fun listener(jobId: String): PluginInstallListener =
    PluginInstallListener { phase, pct, label -> report(jobId, phase, pct, label) }

  fun report(
    jobId: String,
    phase: String,
    pct: Int,
    label: String,
  ) {
    val normalized = if (pct < 0) -1 else pct.coerceIn(0, 100)
    jobs.computeIfPresent(jobId) { _, job ->
      val slot = stageSlot(phase)
      val stages = if (slot == null) job.stages else job.stages + (slot to normalized)
      job.copy(phase = phase, pct = normalized, label = label, stages = stages, modifiedDate = LocalDateTime.now())
    }
  }

  fun done(
    jobId: String,
    instance: PluginInstance,
    label: String,
  ) {
    jobs.computeIfPresent(jobId) { _, job ->
      job.copy(
        phase = PHASE_DONE,
        pct = 100,
        label = label,
        pluginId = instance.pluginId,
        pluginName = instance.name,
        pluginVersion = instance.version,
        stages = job.stages + (STAGE_INSTALL to 100),
        modifiedDate = LocalDateTime.now(),
      )
    }
  }

  fun fail(
    jobId: String,
    message: String,
  ) {
    jobs.computeIfPresent(jobId) { _, job ->
      job.copy(phase = PHASE_ERROR, pct = 0, label = message, error = message, modifiedDate = LocalDateTime.now())
    }
  }

  fun active(): List<PluginInstallJob> = jobs.values.filter { !it.finished }.sortedBy { it.startedDate }

  fun recent(minutes: Long): List<PluginInstallJob> {
    val since = LocalDateTime.now().minusMinutes(minutes)
    return jobs.values
      .filter { it.finished && it.modifiedDate.isAfter(since) }
      .sortedByDescending { it.modifiedDate }
      .take(MAX_RECENT)
  }

  fun dismiss(jobId: String) {
    jobs.remove(jobId)
  }

  private fun sweep() {
    if (jobs.size < MAX_JOBS) return
    val deadline = LocalDateTime.now().minusMinutes(RETAIN_MINUTES)
    jobs.entries.removeIf { it.value.finished && it.value.modifiedDate.isBefore(deadline) }
  }

  private companion object {
    private const val RETAIN_MINUTES = 30L
    private const val MAX_JOBS = 64
    private const val MAX_RECENT = 10
  }
}
