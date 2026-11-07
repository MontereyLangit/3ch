package io._3ch.bbs.application.admin

import io._3ch.bbs.domain.admin.BoardLinks
import io._3ch.bbs.domain.posting.moderation.ModerationRuleSet
import munit.FunSuite

class BoardLinksSuite extends FunSuite {
  test("bbsmenu.html の /板名/ のリンクから板名を拾い、それ以外のリンクは無視する") {
    val html = """<A HREF=https://3ch.io/news/>ニュース</A><br><a href="/sandbox/">砂場</a> <a href='//3ch.io/test/index.html'>t</a>
      |<a href="https://3ch.io/news/">重複</a><a href="https://example.com/">外部</a><a href="/test/read.cgi/news/1/">スレ</a><a href="#top">上</a>""".stripMargin
    assertEquals(BoardLinks.fromMenu(html), List("news", "sandbox", "test"))
  }

  test("規制ルールのうち板の正規表現が一致する行だけを返す") {
    val text = "# コメント\n^news$<><><>deny\n<>AS1-<><>TateDame\n^other$<><>語<>deny\n[<><><>deny\n\n"
    assertEquals(ModerationRuleSet.linesFor(text, "news"), List("^news$<><><>deny", "<>AS1-<><>TateDame"))
  }
}
