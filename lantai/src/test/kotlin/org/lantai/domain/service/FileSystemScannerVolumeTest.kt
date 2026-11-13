package org.lantai.domain.service

import com.google.common.jimfs.Configuration
import com.google.common.jimfs.Jimfs
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class FileSystemScannerVolumeTest {
  private val scanner = FileSystemScanner(emptyList(), emptyList())

  @Test
  fun `given sibling directories sharing a prefix when grouping then they become volumes of the parent series`() {
    Jimfs.newFileSystem(Configuration.unix()).use { fs ->
      val root = rootOf(fs)
      val parent = makeDir(root, "乌龙院四格系列")
      makeDir(parent, "乌龙院四格系列-金毛华佗").let {
        makeFile(it, "01.cbz")
        makeFile(it, "02.cbz")
      }
      makeDir(parent, "乌龙院四格系列-花木兰").let {
        makeFile(it, "01.cbz")
      }

      val scan = scanner.scanRootFolder(root, volumeGrouping = true).series

      assertThat(scan).hasSize(1)
      val (series, books) = scan.entries.first()
      assertThat(series.name).isEqualTo("乌龙院四格系列")
      assertThat(books).hasSize(3)
      assertThat(books.map { it.volume }).containsExactlyInAnyOrder("金毛华佗", "金毛华佗", "花木兰")
    }
  }

  @Test
  fun `given sibling directories sharing a prefix when grouping is off then each directory stays a series`() {
    Jimfs.newFileSystem(Configuration.unix()).use { fs ->
      val root = rootOf(fs)
      val parent = makeDir(root, "乌龙院四格系列")
      makeDir(parent, "乌龙院四格系列-金毛华佗").let { makeFile(it, "01.cbz") }
      makeDir(parent, "乌龙院四格系列-花木兰").let { makeFile(it, "01.cbz") }

      val scan = scanner.scanRootFolder(root).series

      assertThat(scan).hasSize(2)
      assertThat(scan.keys.map { it.name })
        .containsExactlyInAnyOrder("乌龙院四格系列-金毛华佗", "乌龙院四格系列-花木兰")
      assertThat(scan.values.flatten().map { it.volume }).containsOnly("")
    }
  }

  @Test
  fun `given numbered volume directories when grouping then the parent name is used as series`() {
    Jimfs.newFileSystem(Configuration.unix()).use { fs ->
      val root = rootOf(fs)
      val parent = makeDir(root, "某作品")
      makeDir(parent, "第01卷").let { makeFile(it, "01.cbz") }
      makeDir(parent, "第02卷").let { makeFile(it, "01.cbz") }

      val scan = scanner.scanRootFolder(root, volumeGrouping = true).series

      assertThat(scan).hasSize(1)
      val (series, books) = scan.entries.first()
      assertThat(series.name).isEqualTo("某作品")
      assertThat(books.map { it.volume }).containsExactlyInAnyOrder("第01卷", "第02卷")
    }
  }

  @Test
  fun `given sibling directories without shared prefix when grouping then they stay separate series`() {
    Jimfs.newFileSystem(Configuration.unix()).use { fs ->
      val root = rootOf(fs)
      val parent = makeDir(root, "混合")
      makeDir(parent, "甲").let { makeFile(it, "01.cbz") }
      makeDir(parent, "乙").let { makeFile(it, "01.cbz") }

      val scan = scanner.scanRootFolder(root, volumeGrouping = true).series

      assertThat(scan).hasSize(2)
      assertThat(scan.keys.map { it.name }).containsExactlyInAnyOrder("甲", "乙")
    }
  }

  @Test
  fun `given parent directory holding books itself when grouping then children are not merged`() {
    Jimfs.newFileSystem(Configuration.unix()).use { fs ->
      val root = rootOf(fs)
      val parent = makeDir(root, "有书的父")
      makeFile(parent, "loose.cbz")
      makeDir(parent, "作品-甲").let { makeFile(it, "01.cbz") }
      makeDir(parent, "作品-乙").let { makeFile(it, "01.cbz") }

      val scan = scanner.scanRootFolder(root, volumeGrouping = true).series

      assertThat(scan).hasSize(3)
      assertThat(scan.keys.map { it.name }).containsExactlyInAnyOrder("有书的父", "作品-甲", "作品-乙")
    }
  }

  @Test
  fun `given preview request then groups are reported without scanning into series`() {
    Jimfs.newFileSystem(Configuration.unix()).use { fs ->
      val root = rootOf(fs)
      val parent = makeDir(root, "乌龙院四格系列")
      makeDir(parent, "乌龙院四格系列-金毛华佗").let {
        makeFile(it, "01.cbz")
        makeFile(it, "02.cbz")
      }
      makeDir(parent, "乌龙院四格系列-花木兰").let { makeFile(it, "01.cbz") }
      makeDir(root, "独立系列").let { makeFile(it, "01.cbz") }

      val preview = scanner.previewVolumeGrouping(root)

      assertThat(preview.dirs).isEqualTo(3)
      assertThat(preview.series).isEqualTo(2)
      assertThat(preview.groups).hasSize(1)
      val group = preview.groups.first()
      assertThat(group.seriesName).isEqualTo("乌龙院四格系列")
      assertThat(group.volumes.associate { it.name to it.bookCount })
        .containsExactlyInAnyOrderEntriesOf(mapOf("金毛华佗" to 2, "花木兰" to 1))
      assertThat(group.bookCount).isEqualTo(3)
    }
  }

  @Test
  fun `given sibling directories sharing a bare prefix when grouping then the prefix becomes the series name`() {
    Jimfs.newFileSystem(Configuration.unix()).use { fs ->
      val root = rootOf(fs)
      val parent = makeDir(root, "乌龙院系列全集")
      makeDir(parent, "乌龙院-御兽园").let {
        makeFile(it, "01.cbz")
        makeFile(it, "02.cbz")
      }
      makeDir(parent, "乌龙院三十六计").let { makeFile(it, "01.cbz") }
      makeDir(parent, "乌龙院四格系列-偷天换日").let { makeFile(it, "01.cbz") }

      val scan = scanner.scanRootFolder(root, volumeGrouping = true).series

      assertThat(scan).hasSize(1)
      val (series, books) = scan.entries.first()
      assertThat(series.name).isEqualTo("乌龙院")
      assertThat(books).hasSize(4)
      assertThat(books.map { it.volume })
        .containsExactlyInAnyOrder("御兽园", "御兽园", "三十六计", "四格系列-偷天换日")
    }
  }

  @Test
  fun `given bare prefix shorter than the minimum when grouping then directories stay separate series`() {
    Jimfs.newFileSystem(Configuration.unix()).use { fs ->
      val root = rootOf(fs)
      val parent = makeDir(root, "篮球合集")
      makeDir(parent, "篮球飞人").let { makeFile(it, "01.cbz") }
      makeDir(parent, "篮球少年").let { makeFile(it, "01.cbz") }

      val scan = scanner.scanRootFolder(root, volumeGrouping = true).series

      assertThat(scan).hasSize(2)
      assertThat(scan.keys.map { it.name }).containsExactlyInAnyOrder("篮球飞人", "篮球少年")
    }
  }

  @Test
  fun `given bare prefix ending with a digit when grouping then directories stay separate series`() {
    Jimfs.newFileSystem(Configuration.unix()).use { fs ->
      val root = rootOf(fs)
      val parent = makeDir(root, "英文合集")
      makeDir(parent, "Tom1上").let { makeFile(it, "01.cbz") }
      makeDir(parent, "Tom1下").let { makeFile(it, "01.cbz") }

      val scan = scanner.scanRootFolder(root, volumeGrouping = true).series

      assertThat(scan).hasSize(2)
      assertThat(scan.keys.map { it.name }).containsExactlyInAnyOrder("Tom1上", "Tom1下")
    }
  }

  private fun rootOf(fs: java.nio.file.FileSystem): Path = fs.getPath("/root").also { Files.createDirectory(it) }

  private fun makeDir(
    parent: Path,
    name: String,
  ): Path {
    val dir = parent.resolve(name)
    Files.createDirectory(dir)
    return dir
  }

  private fun makeFile(
    dir: Path,
    name: String,
  ) {
    Files.createFile(dir.resolve(name))
  }
}
