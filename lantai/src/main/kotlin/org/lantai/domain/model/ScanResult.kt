package org.lantai.domain.model

data class ScanResult(
  val series: Map<Series, List<Book>>,
  val sidecars: List<Sidecar>,
)
