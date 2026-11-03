package org.lantai.domain.service

import java.nio.file.Path

interface ExtraLogSource {
  val sourceId: String
  val displayName: String
  fun path(): Path?
  fun parse(line: String): LogEntry?
}
