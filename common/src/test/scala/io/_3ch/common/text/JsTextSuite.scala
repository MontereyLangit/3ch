package io._3ch.common.text

class JsTextSuite extends munit.FunSuite {
  test("前後の空白と改行を除き、途中は残す") {
    assertEquals(JsText.trim(" \t　a b\r\n "), "a b")
    assertEquals(JsText.trimEnd(" a \n﻿"), " a")
  }

  test("制御文字や NEL は空白に含めない") {
    assertEquals(JsText.trim("\u0001a\u0085"), "\u0001a\u0085")
  }

  test("空白だけなら空文字列") {
    assertEquals(JsText.trim("   "), "")
    assertEquals(JsText.trimEnd(""), "")
  }
}
