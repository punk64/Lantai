package org.lantai.infrastructure.runtime.install

import java.nio.file.Path
import java.time.Instant

enum class RuntimeInstallPhase {
  IDLE,
  DETECTING,
  DOWNLOADING,
  VERIFYING,
  INSTALLING,
  DONE,
  FAILED,
}

enum class RuntimeSourceKind {
  OFFICIAL,
  MIRROR,
  LOCAL,
  UPLOAD,
}

data class RuntimeCheck(
  val name: String,
  val ok: Boolean,
  val detail: String? = null,
  val hint: String? = null,
)

data class RuntimeDetection(
  val kind: String,
  val platform: String,
  val installed: Boolean,
  val enabled: Boolean = false,
  val ready: Boolean,
  val checks: List<RuntimeCheck>,
  val blockers: List<String> = emptyList(),
)

data class RuntimeSource(
  val label: String,
  val url: String,
  val kind: RuntimeSourceKind,
)

data class RuntimePackageInfo(
  val kind: String,
  val version: String?,
  val fileName: String,
  val size: Long? = null,
  val sources: List<RuntimeSource> = emptyList(),
  val notes: List<String> = emptyList(),
)

data class RuntimePackage(
  val info: RuntimePackageInfo,
  val file: Path,
  val source: RuntimeSourceKind,
)

data class RuntimeProgress(
  val kind: String,
  val phase: RuntimeInstallPhase = RuntimeInstallPhase.IDLE,
  val percent: Int = 0,
  val message: String? = null,
  val version: String? = null,
  val startedAt: Instant? = null,
  val finishedAt: Instant? = null,
)

data class RuntimeStep(
  val title: String,
  val command: String,
  val optional: Boolean = false,
)

data class RuntimeUninstallResult(
  val kind: String,
  val removed: Boolean,
  val dataRemoved: Boolean,
  val message: String? = null,
)

class RuntimeInstallException(
  message: String,
  cause: Throwable? = null,
) : RuntimeException(message, cause)
