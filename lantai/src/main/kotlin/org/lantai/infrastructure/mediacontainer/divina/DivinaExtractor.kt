package org.lantai.infrastructure.mediacontainer.divina

import org.lantai.domain.model.MediaContainerEntry
import org.lantai.domain.model.MediaUnsupportedException
import java.nio.file.Path

interface DivinaExtractor {
  fun mediaTypes(): List<String>

  @Throws(MediaUnsupportedException::class)
  fun getEntries(
    path: Path,
    analyzeDimensions: Boolean,
  ): List<MediaContainerEntry>

  fun getEntryStream(
    path: Path,
    entryName: String,
  ): ByteArray
}
