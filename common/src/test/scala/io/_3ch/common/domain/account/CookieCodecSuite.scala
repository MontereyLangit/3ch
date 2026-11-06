package io._3ch.common.domain.account

import munit.FunSuite

class CookieCodecSuite extends FunSuite {
  private val claims = SessionClaims("abcdefghijklmnop", "user_123", 2, 2000000000, 3)
  private val signature = "AAAAAAAAAAAAAAAAAAAAAA"
  private val payload = "1.abcdefghijklmnop.user_123.2.2000000000.3.."
  private val cookie = payload + "." + signature

  test("セッションは空の端末・紐づけを含む 9 フィールドで往復する") {
    assertEquals(CookieCodec.payload(claims), Some(payload))
    assertEquals(CookieCodec.encode(claims, signature), Some(cookie))
    assertEquals(cookie.split("\\.", -1).length, 9)
    assertEquals(CookieCodec.parse(cookie), Some(SignedSession(payload, claims, signature)))
  }

  test("端末の新旧形式と専ブラの紐づけを保持する") {
    val devices = Seq("", "p0123456789abcdef", "g0123456789abcdef01", "u0123456789abcdef01")
    devices.foreach { device =>
      val expected = claims.copy(device = device, binding = Some(SessionBinding("token123", "0123456789abcdef")))
      val encoded = CookieCodec.encode(expected, signature).get
      assertEquals(CookieCodec.parse(encoded).map(_.claims), Some(expected))
      assert(encoded.contains(".t:token123:0123456789abcdef."))
    }
  }

  test("内部 ID・公開 ID・世代・Lv の書式を確認する") {
    val invalid = Seq(
      claims.copy(aid = "short"), claims.copy(aid = "ABCDEFGHIJKLMNOP"), claims.copy(aid = "abcdefghijklmno_"),
      claims.copy(uid = "ab"), claims.copy(uid = "a" * 17), claims.copy(uid = "User123"), claims.copy(uid = "abc-def"),
      claims.copy(generation = -1), claims.copy(lv = -1), claims.copy(lv = 11))
    invalid.foreach(value => assertEquals(CookieCodec.payload(value), None, value.toString))
    Seq(claims.copy(uid = "abc", generation = 0, lv = 0), claims.copy(uid = "a" * 16, generation = Long.MaxValue, lv = 10))
      .foreach(value => assertEquals(CookieCodec.parse(CookieCodec.encode(value, signature).get).map(_.claims), Some(value)))
  }

  test("端末の粒度・桁数・大文字と紐づけの書式が不正なら拒否する") {
    Seq("0123456789abcdef01", "x0123456789abcdef01", "p0123456789abcdef0", "p0123456789abcdef012", "p0123456789ABCDEF01")
      .foreach(device => assertEquals(CookieCodec.payload(claims.copy(device = device)), None, device))
    Seq(SessionBinding("", "0123456789abcdef"), SessionBinding("A", "0123456789abcdef"), SessionBinding("a" * 65, "0123456789abcdef"),
      SessionBinding("token", "short"), SessionBinding("token", "0123456789ABCDEF"))
      .foreach(binding => assertEquals(CookieCodec.payload(claims.copy(binding = Some(binding))), None))
  }

  test("版・フィールド数・非整数・整数の範囲外と不正な署名を拒否する") {
    val invalid = Seq(
      cookie.replace("1.", "2."), cookie + ".extra", payload,
      cookie.replace(".2.", ".-1."), cookie.replace(".2.", ".2.0."), cookie.replace(".2.", ".9223372036854775808."),
      cookie.replace(".2000000000.", ".2e9."), cookie.replace(".2000000000.", ".Infinity."),
      cookie.replace(".2000000000.", ".9223372036854775808."), cookie.replace(".3.", ".11."),
      payload + "." + "A" * 21, payload + "." + "A" * 21 + "B", payload + "." + signature + "==",
      cookie.replace("..", ".t:token:0123456789abcdef:extra."))
    invalid.foreach(value => assertEquals(CookieCodec.parse(value), None, value))
    assertEquals(CookieCodec.parse(null), None)
  }

  test("Cookie ヘッダは名前を完全一致で読み、同名の重複を拒否する") {
    val expected = CookieCodec.parse(cookie)
    assertEquals(CookieCodec.fromHeader(s"PON=old; ${CookieCodec.Name}=$cookie; other=x"), expected)
    assertEquals(CookieCodec.fromHeader(s"${CookieCodec.Name}=$cookie; ${CookieCodec.Name}=$cookie"), None)
    assertEquals(CookieCodec.fromHeader(s"prefix${CookieCodec.Name}=$cookie"), None)
    assertEquals(CookieCodec.fromHeader(s"${CookieCodec.Name}=\"$cookie\""), None)
    assertEquals(CookieCodec.fromHeader(""), None)
    assertEquals(CookieCodec.fromHeader(null), None)
  }

  test("失効時刻ちょうどと不正な現在時刻は期限切れにする") {
    assert(claims.isCurrent(1999999999.9))
    assert(!claims.isCurrent(2000000000))
    assert(!claims.isCurrent(2000000001))
    Seq(Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity).foreach(now => assert(!claims.isCurrent(now)))
    assert(!claims.copy(expiresAt = -1).isCurrent(0))
  }
}
