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
