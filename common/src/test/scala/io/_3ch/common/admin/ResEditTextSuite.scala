package io._3ch.common.admin

class ResEditTextSuite extends munit.FunSuite {
  test("<br> と前後の空白 1 つずつを改行に戻す") {
    assertEquals(ResEditText.toText("一 <br> 二<BR/>三<br  />四"), "一\n二\n三\n四")
    assertEquals(ResEditText.toText("a  <br>  b"), "a \n b")
  }

  test("<br> の中の空白は JavaScript の \\s と同じ文字を認める") {
    assertEquals(ResEditText.toText("a<br\u3000/>b<br\u00a0>c"), "a\nb\nc")
  }

  test("5 つの文字参照を 1 度だけ戻し、ほかの参照は残す") {
    assertEquals(ResEditText.toText("&lt;b&gt; &quot;x&quot; &apos; &amp;lt; &#39; &nbsp;"), "<b> \"x\" ' &lt; &#39; &nbsp;")
  }

  test("テキストを dat の欄にするときは記号を参照にし、改行を <br> にする") {
    assertEquals(ResEditText.toField("<b>&\"x\"'\r\n二\n三"), "&lt;b&gt;&amp;&quot;x&quot;' <br> 二 <br> 三")
  }

  test("編集用テキストと dat の欄は往復する") {
    val field = "名無し &lt;tag&gt; <br> 二行目 &quot;引用&quot; &amp; 記号"
    assertEquals(ResEditText.toField(ResEditText.toText(field)), field)
    val text = "<script>\n&amp;\n\"x\""
    assertEquals(ResEditText.toText(ResEditText.toField(text)), text)
  }
}

class ResViewSuite extends munit.FunSuite {
  test("dat の行を番号付きの欄と編集用テキストに分け、末尾の空白行は除く") {
    val res = ResView.fromDat("名 &lt;a&gt;<>sage<>日付 ID:x<> 本文 <br> 二 <>題\n欠け<>\n\n")
    assertEquals(res.map(_.number), List(1, 2))
    assertEquals(res.head, ResView(1, "名 &lt;a&gt;", "sage", "日付 ID:x", " 本文 <br> 二 ", ResEditFields("名 <a>", "sage", " 本文\n二 ")))
    assertEquals(res(1), ResView(2, "欠け", "", "", "", ResEditFields("欠け", "", "")))
  }
}
