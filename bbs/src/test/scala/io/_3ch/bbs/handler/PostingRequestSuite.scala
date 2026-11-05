package io._3ch.bbs.handler

import munit.FunSuite
import io._3ch.bbs.domain.posting.PostingError
import io._3ch.bbs.external.web.Request
import scala.scalajs.js

/** bbs.cgi のヘッダの読み取りと検査 */
class PostingRequestSuite extends FunSuite {
  private def makeRequest(method: String = "POST", headers: Seq[(String, String)] = Nil, cf: js.UndefOr[js.Dictionary[js.Any]] = js.undefined): Request = {
    val lowered = headers.map((k, v) => k.toLowerCase -> v).toMap
    js.Dynamic.literal(
      method = method,
      cf = cf,
      headers = js.Dynamic.literal(get = ((name: String) => lowered.getOrElse(name.toLowerCase, null)): js.Function1[String, String])
    ).asInstanceOf[Request]
  }

  private val valid = PostingRequest.from(makeRequest(headers = Seq(
    "content-type" -> "application/x-www-form-urlencoded",
    "referer" -> "https://www.3chbbs.com/test/read.cgi/sample/1/",
    "user-agent" -> "Mozilla/5.0 (TestBrowser)",
    "CF-Connecting-IP" -> "192.0.2.1"
  )))

  test("通常のリクエストは検査を通る") {
    assertEquals(valid.validate, None)
  }

  test("判定は method → content-type → referer → UA → client-ip の順") {
    val all = valid.copy(method = "GET", contentType = "text/plain", referer = "", userAgent = "x", clientIp = "1.2.3.4")
    assertEquals(all.validate, Some(PostingError.MethodNotPost))
    assertEquals(all.copy(method = "POST").validate, Some(PostingError.BrokenBody("text/plain")))
    assertEquals(all.copy(method = "POST", contentType = valid.contentType).validate, Some(PostingError.InvalidReferer("")))
    assertEquals(all.copy(method = "POST", contentType = valid.contentType, referer = valid.referer).validate, Some(PostingError.InvalidUserAgent("x")))
    assertEquals(valid.copy(clientIp = "1.2.3.4").validate, Some(PostingError.ProxyHeader("1.2.3.4")))
  }

  test("content-type は application/x-www-form-urlencoded を含めばよい") {
    assertEquals(valid.copy(contentType = "application/x-www-form-urlencoded; charset=UTF-8").validate, None)
    assertEquals(valid.copy(contentType = "").validate, Some(PostingError.BrokenBody("")))
  }

  test("referer は 3ch.io・3chbbs.com（ポート付き可）と 127.0.0.1:8787・8788 を通す") {
    val accepted = List(
      "https://www.3ch.io", "http://www.3ch.io/test/", "https://www.3chbbs.com?x", "https://www.3chbbs.com#a",
      "https://www.3ch.io:8443/", "http://127.0.0.1:8787/test/", "http://127.0.0.1:8788"
    )
    val rejected = List(
      "", "https://3ch.io/", "https://www.3ch.io.evil.example/", "https://www.3chbbs.com.evil/", "https://www.3ch.io:/",
      "http://127.0.0.1:8789/", "https://127.0.0.1:8787/", "http://localhost:8787/", "ftp://www.3ch.io/"
    )
    accepted.foreach(referer => assertEquals(valid.copy(referer = referer).validate, None, referer))
    rejected.foreach(referer => assertEquals(valid.copy(referer = referer).validate, Some(PostingError.InvalidReferer(referer)), referer))
  }

  test("UA は 7〜384 文字の ASCII 印字文字だけ") {
    assertEquals(valid.copy(userAgent = "a" * 7).validate, None)
    assertEquals(valid.copy(userAgent = "a" * 384).validate, None)
    for (ua <- List("a" * 6, "a" * 385, "Mozilla/５.0", "Mozilla\t5.0")) {
      assertEquals(valid.copy(userAgent = ua).validate, Some(PostingError.InvalidUserAgent(ua)), ua)
    }
  }

  test("host は接続元 IP と異なる最初の転送ヘッダを付ける") {
    def host(headers: (String, String)*): String = PostingRequest.from(makeRequest(headers = headers)).host
    assertEquals(host("CF-Connecting-IP" -> "192.0.2.1"), "192.0.2.1")
    assertEquals(host("CF-Connecting-IP" -> "192.0.2.1", "x-forwarded-for" -> "192.0.2.1"), "192.0.2.1")
    assertEquals(host("CF-Connecting-IP" -> "192.0.2.1", "x-forwarded-for" -> "192.0.2.1", "forwarded" -> "for=203.0.113.5"), "192.0.2.1<for=203.0.113.5>")
    assertEquals(host("CF-Connecting-IP" -> "192.0.2.1", "x-forwarded-for" -> "203.0.113.5", "forwarded" -> "for=x", "via" -> "1.1 proxy"), "192.0.2.1<203.0.113.5>")
    assertEquals(host("CF-Connecting-IP" -> "192.0.2.1", "via" -> "1.1 proxy"), "192.0.2.1<1.1 proxy>")
    assertEquals(host("x-forwarded-for" -> "203.0.113.5"), "null<203.0.113.5>")
    assertEquals(host(), "null")
  }

  test("browserUserAgent は full-version-list があればそれを、無ければ sec-ch-ua を連結する") {
    def ua(headers: (String, String)*): String = PostingRequest.from(makeRequest(headers = ("user-agent" -> "UA") +: headers)).browserUserAgent
    assertEquals(ua("sec-ch-ua" -> "A", "sec-ch-ua-full-version-list" -> "B"), "UAB")
    assertEquals(ua("sec-ch-ua" -> "A", "sec-ch-ua-full-version-list" -> ""), "UA")
    assertEquals(ua("sec-ch-ua" -> "A"), "UAA")
    assertEquals(ua(), "UA")
  }

  test("isMonazilla は UA と Client Hints の連結が Monazilla/1.00 で始まるか") {
    assertEquals(valid.copy(userAgent = "Monazilla/1.00 JaneStyle/4.23").isMonazilla, true)
    assertEquals(valid.copy(userAgent = "", secChUa = "Monazilla/1.00").isMonazilla, true)
    assertEquals(valid.copy(userAgent = "Mozilla/5.0 Monazilla/1.00").isMonazilla, false)
    assertEquals(valid.copy(userAgent = "Monazilla/1.01").isMonazilla, false)
  }

  test("from は無いヘッダを空文字列、cf が無ければ地域情報を None にする") {
    val empty = PostingRequest.from(makeRequest(method = "GET"))
    assertEquals((empty.method, empty.contentType, empty.cookie, empty.clientIp), ("GET", "", "", ""))
    assertEquals((empty.secChUaFullVersionList, empty.connectingIp), (None, None))
    assertEquals((empty.asn, empty.country, empty.region, empty.city), (None, None, None, None))
  }

  test("from は cf の値を文字列にし、null・undefined は None にする") {
    val cf = js.Dictionary[js.Any]("asn" -> 2516, "country" -> "JP", "region" -> null, "city" -> js.undefined)
    val req = PostingRequest.from(makeRequest(cf = cf))
    assertEquals((req.asn, req.country, req.region, req.city), (Some("2516"), Some("JP"), None, None))
  }
}
