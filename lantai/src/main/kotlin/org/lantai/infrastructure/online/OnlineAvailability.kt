package org.lantai.infrastructure.online

data class OnlineSourceStatus(
  val kind: String,
  val name: String,
  val installed: Boolean,
  val enabled: Boolean,
  val running: Boolean,
) {
  val contentVisible: Boolean get() = installed && enabled

  val ready: Boolean get() = contentVisible && running
}

data class OnlineStatusSummary(
  val ready: Boolean,
  val contentReady: Boolean,
  val providers: List<OnlineSourceStatus>,
)

object OnlineAvailability {
  @Volatile
  private var hidden: Set<String> = emptySet()

  fun hiddenKinds(): Set<String> = hidden

  fun update(kinds: Set<String>) {
    hidden = kinds
  }
}
