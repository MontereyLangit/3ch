package io._3ch.common.text

import scala.scalajs.js

/** JsText が実行環境の trim・trimEnd と一致するか */
class JsTextRuntimeSuite extends munit.FunSuite {
  test("BMP の全文字で空白の判定が trim と一致する") {
    (0 until 0x10000).foreach { code =>
      val c = code.toChar
      val native = js.Dynamic.global.String.fromCharCode(code).trim().asInstanceOf[String].isEmpty
      assertEquals(JsText.isWhitespace(c), native, f"U+$code%04X")
    }
  }

  test("trimEnd は前の空白を残す") {
    val s = " 　a b \n"
    assertEquals(JsText.trimEnd(s), s.asInstanceOf[js.Dynamic].trimEnd().asInstanceOf[String])
  }
}
