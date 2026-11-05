package io._3ch.common.domain

import munit.FunSuite

class PostingResponseSuite extends FunSuite {

  private def page(title: String, error: Option[String] = None, refresh: Option[String] = None) =
    PostingPage(title, error, refresh, " 概要 ", " 規約 ")

  test("書きこみました。は meta refresh の遷移先を取り出す") {
    val outcome = PostingResponse.classify(page("書きこみました。", refresh = Option("5;URL=/test/read.cgi/sample/1800000001/")))
    assertEquals(outcome, PostingOutcome.Posted(Option("/test/read.cgi/sample/1800000001/")))
  }

  test("遷移先が無い・外部 URL・プロトコル相対は redirect なし") {
    assertEquals(PostingResponse.classify(page("書きこみました。")), PostingOutcome.Posted(None))
    assertEquals(PostingResponse.refreshUrl("5;URL=https://example.com/"), None)
    assertEquals(PostingResponse.refreshUrl("5;URL=//example.com/"), None)
    assertEquals(PostingResponse.refreshUrl("5;URL=javascript:alert(1)"), None)
    assertEquals(PostingResponse.refreshUrl("garbage"), None)
    assertEquals(PostingResponse.refreshUrl("0; url = /a/b/"), Option("/a/b/"))
  }

  test("ＥＲＲＯＲ！ はエラー文言を返し、文言が無ければ不明") {
    assertEquals(PostingResponse.classify(page("ＥＲＲＯＲ！", error = Option("ERROR: 本文がありません。"))), PostingOutcome.Rejected("ERROR: 本文がありません。"))
    assertEquals(PostingResponse.classify(page("ＥＲＲＯＲ！")), PostingOutcome.Unrecognized)
  }

  test("書き込み確認は概要と規約を trim して返す") {
    assertEquals(PostingResponse.classify(page("■ 書き込み確認 ■")), PostingOutcome.NeedsCookie("概要", "規約"))
  }

  test("未知の title は不明") {
    assertEquals(PostingResponse.classify(page("404 Not Found")), PostingOutcome.Unrecognized)
  }

  test("charset は Content-Type から取り、無ければ utf-8") {
    assertEquals(PostingResponse.charset("text/html; charset=Shift_JIS"), "Shift_JIS")
    assertEquals(PostingResponse.charset("text/html;charset=\"UTF-8\""), "UTF-8")
    assertEquals(PostingResponse.charset("text/html"), "utf-8")
  }
}
