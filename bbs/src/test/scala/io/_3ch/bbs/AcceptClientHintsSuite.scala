package io._3ch.bbs

import io._3ch.bbs.external.web.Response
import io._3ch.bbs.handler.Responses
import munit.FunSuite
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*

class AcceptClientHintsSuite extends FunSuite {
  test("変更できないリダイレクト応答を複製しLocationと状態を保つ") {
    val original = js.Dynamic.global.Response.redirect("https://example.invalid/next", 302).asInstanceOf[Response]
    intercept[js.JavaScriptException](original.headers.set("Accept-CH", "invalid"))
    val copy = Main.acceptClientHints(original)
    assertEquals(copy.status, 302)
    assertEquals(copy.statusText, original.statusText)
    assertEquals(copy.headers.get("Location"), "https://example.invalid/next")
    assert(copy.headers.get("Accept-CH").contains("Sec-CH-UA-Model"))
    assertEquals(original.headers.get("Accept-CH"), null)
  }

  test("fetchが返す変更できないヘッダと本文ストリームを保つ") {
    js.Dynamic.global.fetch("data:text/plain,streamed-body").asInstanceOf[js.Promise[Response]].toFuture.flatMap { original =>
      intercept[js.JavaScriptException](original.headers.set("Accept-CH", "invalid"))
      val copy = Main.acceptClientHints(original)
      assertEquals(copy.status, 200)
      assertEquals(copy.statusText, original.statusText)
      assertEquals(copy.headers.get("Content-Type"), original.headers.get("Content-Type"))
      assertEquals(original.headers.get("Accept-CH"), null)
      assert(copy.headers.get("Accept-CH").contains("Sec-CH-UA-Model"))
      copy.text().toFuture.map(body => assertEquals(body, "streamed-body"))
    }
  }

  test("304の空本文と既存のCookieヘッダを保つ") {
    val unchanged = Main.acceptClientHints(Responses.response(null, 304, js.Dictionary("ETag" -> "\"same\"")))
    assertEquals(unchanged.status, 304)
    assertEquals(unchanged.body, null)
    assertEquals(unchanged.headers.get("ETag"), "\"same\"")
    val original = Responses.response("body")
    original.headers.append("Set-Cookie", "one=1; Path=/")
    original.headers.append("Set-Cookie", "two=2; Path=/")
    val copy = Main.acceptClientHints(original)
    val cookies = copy.headers.asInstanceOf[js.Dynamic].getSetCookie().asInstanceOf[js.Array[String]].toVector
    assertEquals(cookies, Vector("one=1; Path=/", "two=2; Path=/"))
  }
}
