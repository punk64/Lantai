package org.lantai.infrastructure.i18n

import com.ibm.icu.text.Transliterator

object SeriesFirstLetter {
  private val translit = ThreadLocal.withInitial { Transliterator.getInstance("Han-Latin; Latin-ASCII") }

  fun normalizeLetter(raw: String?): String {
    val s = (raw ?: "").trim().uppercase()
    if (s.isEmpty()) return "#"
    val c = s[0]
    return if (c in 'A'..'Z') c.toString() else "#"
  }

  fun of(title: String?): String {
    val t = (title ?: "").trim()
    if (t.isEmpty()) return "#"
    val c = t[0]
    if (c in '0'..'9') return "#"
    if (c.code < '\u0080'.code) {
      val u = c.uppercaseChar()
      return if (u in 'A'..'Z') u.toString() else "#"
    }
    val py = translit.get().transliterate(t).trim()
    val f = py.firstOrNull() ?: return "#"
    if (f.code >= '\u0080'.code) return "#"
    val u = f.uppercaseChar()
    return if (u in 'A'..'Z') u.toString() else "#"
  }
}
