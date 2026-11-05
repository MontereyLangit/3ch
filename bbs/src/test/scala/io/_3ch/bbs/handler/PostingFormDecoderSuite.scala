package io._3ch.bbs.handler

import munit.FunSuite
import io._3ch.bbs.external.web.TextEncoder
import io._3ch.bbs.util.SjisEncoding
import io._3ch.bbs.domain.posting.PostingError
import io._3ch.common.domain.posting.PostForm

import scala.scalajs.js
import scala.scalajs.js.typedarray.Uint8Array

/** bbs.cgi の文字コード判定とフォーム復号 */
class PostingFormDecoderSuite extends FunSuite {
  private def sjis(fields: (String, String)*): Uint8Array = {
    def encode(v: String): String = {
      val bytes = SjisEncoding.encode(v)
      (0 until bytes.length).map(i => f"%%${bytes(i).toInt & 0xff}%02X").mkString
    }
    new TextEncoder().encode((fields :+ ("submit" -> "書き込む")).map((k, v) => s"$k=${encode(v)}").mkString("&"))
  }

  private def decoded(body: Uint8Array, isMonazilla: Boolean = false): PostForm =
    PostingFormDecoder.decode(body, isMonazilla).fold(left => fail(s"rejected: $left"), identity)

  test("同じ名前の項目は後勝ち、値の 2 個目以降の = は捨てる") {
    val body = new TextEncoder().encode("MESSAGE=first&MESSAGE=a=b&submit=%E6%9B%B8")
    assertEquals(decoded(body).message, "a")
  }

  test("submit が無ければ NoSubmit、Web からの Shift_JIS は SjisFromWeb") {
    assertEquals(PostingFormDecoder.decode(new TextEncoder().encode("MESSAGE=x"), isMonazilla = false), Left(PostingError.NoSubmit -> PostForm()))
    assertEquals(PostingFormDecoder.decode(sjis("MESSAGE" -> "x"), isMonazilla = false), Left(PostingError.SjisFromWeb -> PostForm(isSJIS = true)))
  }

  test("Monazilla の Shift_JIS は復号する") {
    val form = decoded(sjis("MESSAGE" -> "日本語", "FROM" -> "名前"), isMonazilla = true)
    assertEquals(form.message, "日本語")
    assertEquals(form.name, "名前")
    assert(form.isSJIS)
  }

  test("UTF-8の復号後にHTML整形を行い、+は空白、%2Bは+にする") {
    val body = new TextEncoder().encode("MESSAGE=%E6%97%A5%E6%9C%AC%E8%AA%9E%3C%26+%2B&FROM=%E5%90%8D%E5%89%8D!id&submit=%e6%9b%b8")
    val form = decoded(body)
    assertEquals(form.message, "日本語&lt;&amp; +")
    assertEquals((form.name, form.showId, form.isSJIS), ("名前", true, false))
  }

  test("UTF-8の不正percentは後の重複値で隠れず、submitの検査が復号に先行する") {
    val malformed = new TextEncoder().encode("MESSAGE=%GG&MESSAGE=ok&submit=%E6%9B%B8")
    intercept[js.JavaScriptException](PostingFormDecoder.decode(malformed, isMonazilla = false))
    val missingSubmit = new TextEncoder().encode("MESSAGE=%GG")
    assertEquals(PostingFormDecoder.decode(missingSubmit, isMonazilla = false), Left(PostingError.NoSubmit -> PostForm()))
    assertEquals(PostingFormDecoder.decode(new TextEncoder().encode("MESSAGE=%GG&submit=x"), isMonazilla = false), Left(PostingError.SjisFromWeb -> PostForm(isSJIS = true)))
  }

  test("Shift_JISの不正percentは文字として残す") {
    val body = new TextEncoder().encode("MESSAGE=%GG%2%41&submit=x")
    assertEquals(decoded(body, isMonazilla = true).message, "%GG%2A")
  }
}
