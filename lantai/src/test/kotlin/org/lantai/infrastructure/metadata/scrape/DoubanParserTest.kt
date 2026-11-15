package org.lantai.infrastructure.metadata.scrape

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DoubanParserTest {
  private fun payload(): String {
    val body = javaClass.getResource("/douban/search.json")!!.readText()
    return "window.__DATA__ = $body"
  }

  @Test
  fun `parses subject items from a real search payload`() {
    val candidates = DoubanParser.parse(payload(), 10)

    assertThat(candidates).hasSize(10)

    val first = candidates.first()
    assertThat(first.source).isEqualTo(DOUBAN_SOURCE_ID)
    assertThat(first.sourceId).isEqualTo("2567698")
    assertThat(first.title).isEqualTo("三体")
    assertThat(first.subtitle).isEqualTo("“地球往事”三部曲之一")
    assertThat(first.authors).containsExactly("刘慈欣")
    assertThat(first.publisher).isEqualTo("重庆出版社")
    assertThat(first.releaseDate).isEqualTo("2008-1")
    assertThat(first.rating).isEqualTo(8.9)
    assertThat(first.ratingCount).isEqualTo(519035)
    assertThat(first.coverUrl).startsWith("https://")
    assertThat(first.url).contains("/subject/2567698/")
  }

  @Test
  fun `keeps multiple authors in order`() {
    val html =
      """
      window.__DATA__ = {"items":[{"id":2,"tpl_name":"search_subject","title":"某书","abstract":"[美] 甲 / 乙 / 某出版社 / 2020-3 / 59.00","cover_url":"","url":"https://book.douban.com/subject/2/"}]}
      """.trimIndent()

    val candidate = DoubanParser.parse(html, 10).single()
    assertThat(candidate.authors).containsExactly("[美] 甲", "乙")
    assertThat(candidate.publisher).isEqualTo("某出版社")
    assertThat(candidate.releaseDate).isEqualTo("2020-3")
  }

  @Test
  fun `respects the requested limit`() {
    assertThat(DoubanParser.parse(payload(), 3)).hasSize(3)
  }

  @Test
  fun `returns empty when the data marker is absent`() {
    assertThat(DoubanParser.parse("<html><body>blocked</body></html>", 10)).isEmpty()
  }

  @Test
  fun `returns empty when the payload is not valid json`() {
    assertThat(DoubanParser.parse("window.__DATA__ = {broken", 10)).isEmpty()
  }

  @Test
  fun `parses abstract without price`() {
    val html =
      """
      window.__DATA__ = {"items":[{"id":1,"tpl_name":"search_subject","title":"某书","abstract":"某人 / 某出版社 / 2019-5","cover_url":"","url":"https://book.douban.com/subject/1/"}]}
      """.trimIndent()

    val candidate = DoubanParser.parse(html, 10).single()
    assertThat(candidate.authors).containsExactly("某人")
    assertThat(candidate.publisher).isEqualTo("某出版社")
    assertThat(candidate.releaseDate).isEqualTo("2019-5")
  }
}
