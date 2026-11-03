package org.lantai.infrastructure.lantai

import org.lantai.domain.model.DomainEvent
import org.lantai.domain.service.ReadingStatsRecorder
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class ReadingProgressListener(
  private val readingStatsRecorder: ReadingStatsRecorder,
) {
  @EventListener
  fun onReadProgressChanged(event: DomainEvent.ReadProgressChanged) {
    val progress = event.progress
    readingStatsRecorder.notePage(
      userId = progress.userId,
      bookId = progress.bookId,
      page = progress.page,
      deviceId = progress.deviceId,
      deviceName = progress.deviceName,
    )
  }
}
