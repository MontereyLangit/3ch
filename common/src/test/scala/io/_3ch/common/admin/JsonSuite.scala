package io._3ch.common.admin

import io._3ch.common.admin.Json.{Arr, Bool, Num, Obj, Str}

class JsonSuite extends munit.FunSuite {
  test("値を読み、キーの順を保つ") {
    val parsed = Json.parse(""" { "b" : [1, -2.5e1, true, false, null], "a": "x\"\\\/\n\u3042" } """)
    assertEquals(parsed, Right(Obj(List("b" -> Arr(List(Num(1), Num(-25), Bool(true), Bool(false), Json.Null)), "a" -> Str("x\"\\/\nあ")))))
  }

  test("書いた文字列を読むと同じ値になる") {
    val value = Obj(List("s" -> Str("制御\u0001\t\r\n\"\\\u2028"), "n" -> Num(1234567890123d), "f" -> Num(0.5), "e" -> Arr(Nil), "o" -> Obj(Nil)))
    val text = Json.render(value)
    assertEquals(text, """{"s":"制御\u0001\t\r\n\"\\\u2028","n":1234567890123,"f":0.5,"e":[],"o":{}}""")
    assertEquals(Json.parse(text), Right(value))
  }

  test("不正な JSON は理由を返す") {
    List("", "{", "[1,]", "{\"a\" 1}", "01", "1.", "tru", "\"\u0001\"", "\"\\x\"", "{} x", "-", "\"\\u12\"").foreach { text =>
      assert(Json.parse(text).isLeft, text)
    }
  }

  test("深すぎる入れ子は拒否する") {
    assert(Json.parse("[" * 100 + "]" * 100).isLeft)
    assert(Json.parse("[" * 10 + "]" * 10).isRight)
  }

  test("同じキーは後の値を引く") {
    assertEquals(Json.parse("""{"a":1,"a":2}""").toOption.flatMap(_.get("a")), Some(Num(2)))
  }
}
