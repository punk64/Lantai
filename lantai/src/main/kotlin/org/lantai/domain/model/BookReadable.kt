package org.lantai.domain.model

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension

private val CONVERTIBLE_EXTENSIONS = setOf("mobi", "azw", "azw3", "prc", "fb2", "lit", "pdb")

fun convertedEpubPathFor(path: Path): Path {
  val name = path.fileName.toString()
  val dot = name.lastIndexOf('.')
  val base = if (dot > 0) name.substring(0, dot) else name
  return path.resolveSibling(".$base.converted.epub")
}

fun Book.convertedEpubPath(): Path = convertedEpubPathFor(path)

fun Book.readablePath(): Path {
  if (path.extension.lowercase() !in CONVERTIBLE_EXTENSIONS) return path
  val converted = convertedEpubPath()
  return if (Files.exists(converted)) converted else path
}

fun Book.isConverted(): Boolean = Files.exists(convertedEpubPath())

fun Book.isConvertible(): Boolean = path.extension.lowercase() in CONVERTIBLE_EXTENSIONS
