package org.lantai.infrastructure.upgrade

internal fun compareVersions(
  a: String,
  b: String,
): Int {
  val pa = versionParts(a)
  val pb = versionParts(b)
  for (i in 0 until maxOf(pa.size, pb.size)) {
    val x = pa.getOrElse(i) { 0 }
    val y = pb.getOrElse(i) { 0 }
    if (x != y) return x.compareTo(y)
  }
  return 0
}

private fun versionParts(version: String): List<Int> =
  version
    .trim()
    .removePrefix("v")
    .split('.', '-', '+', '_')
    .map { it.takeWhile { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }
