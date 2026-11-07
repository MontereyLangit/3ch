package io._3ch.bbs.application.admin

import io._3ch.common.admin.ResEditText
import munit.FunSuite
import scala.scalajs.js

/** ResEditText が移設前の JavaScript の正規表現による変換と同じ結果になること */
class ResEditTextJsSuite extends FunSuite {
  private def previous(v: String): String = {
    val entities = Map("&lt;" -> "<", "&gt;" -> ">", "&quot;" -> "\"", "&apos;" -> "'", "&amp;" -> "&")
    val replace: js.Function1[String, String] = s => entities(s)
    v.asInstanceOf[js.Dynamic].replace(new js.RegExp(" ?<br\\s*/?>(?: )?", "gi"), "\n").replace(new js.RegExp("&(?:lt|gt|quot|apos|amp);", "g"), replace).asInstanceOf[String]
  }

  test("dat の欄から編集用テキストへの変換は移設前と同じ") {
    val samples = List("", "a <br> b", "a<br>b", "a  <BR />  b", "<br\t\n/>", "<br　>", "<br ﻿/>", "<bR >x", "<brx>", "< br>",
      "&amp;lt;&lt;&gt;&quot;&apos;&#39;&nbsp;&AMP;", "名無し &lt;tag&gt; <br>  <br> 末尾 ", "\u0085<br\u0085>", "<br᠎>", "<br​>")
    samples.foreach(sample => assertEquals(ResEditText.toText(sample), previous(sample), js.JSON.stringify(sample)))
  }
}
