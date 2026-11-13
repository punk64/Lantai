package org.lantai

import org.lantai.domain.model.Book
import org.lantai.domain.model.ScanResult
import org.lantai.domain.model.Series

fun Map<Series, List<Book>>.toScanResult() = ScanResult(this, emptyList())
