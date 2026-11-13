package org.lantai.domain.model

object PluginCapabilities {
  val RELEASED: Set<String> =
    setOf(
      "library.read",
      "metadata.write",
      "progress.write",
      "library.write",
      "library.import",
      "event.observe",
      "task.schedule",
      "storage.blob",
      "tts.speak",
      "notify.send",
      "credential.store",
      "source.provide",
      "metadata.provide",
      "font.provide",
      "page.transform",
      "text.transform",
      "search.extend",
      "convert.provide",
      "ui.panel",
    )

  val CANDIDATE: Set<String> = emptySet()

  val KNOWN: Set<String> = RELEASED + CANDIDATE

  fun isKnown(capability: String): Boolean = capability.trim() in KNOWN
}
