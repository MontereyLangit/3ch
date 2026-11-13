package io._3ch.bbs.handler

import io._3ch.bbs.handler.HandlerTestKit.*
import munit.FunSuite
import scala.scalajs.js

class ResponsesSuite extends FunSuite {
  private def entries(res: js.Dynamic): List[(String, String)] = {
    val out = js.Array[(String, String)]()
    res.headers.forEach(((value: String, name: String) => { out.push(name -> value); () }): js.Function2[String, String, Unit])
    out.toList
  }

  test("同名のヘッダを複数渡すと Set-Cookie を別々に出す") {
    val res = Responses.plain("body", "html", extra = Seq("Set-Cookie" -> "A=1; path=/", "set-cookie" -> "B=2; path=/", "x-resnum" -> "2")).asInstanceOf[js.Dynamic]
    assertEquals(entries(res).filter(_._1 == "set-cookie"), List("set-cookie" -> "A=1; path=/", "set-cookie" -> "B=2; path=/"))
    assertEquals(res.headers.getSetCookie().asInstanceOf[js.Array[String]].toList, List("A=1; path=/", "B=2; path=/"))
    assertEquals(header(res, "x-resnum"), Some("2"))
  }

  test("1 本だけなら既存のヘッダを置き換え、従来と同じ出力になる") {
    val res = Responses.plain("body", "plain", extra = Seq("Accept-Ranges" -> "none", "Set-Cookie" -> "PON=x; HttpOnly")).asInstanceOf[js.Dynamic]
    val headers = entries(res)
    assertEquals(headers.filter(_._1 == "accept-ranges"), List("accept-ranges" -> "none"))
    assertEquals(headers.filter(_._1 == "set-cookie"), List("set-cookie" -> "PON=x; HttpOnly"))
    assertEquals(headers.map(_._1), List("accept-ranges", "content-length", "content-type", "last-modified", "set-cookie"))
  }
}
