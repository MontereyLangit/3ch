package ch3s.bbs.domain.posting

import munit.FunSuite

class KeyIpSuite extends FunSuite {
  test("IPv4 はそのまま") {
    assertEquals(KeyIp.of("192.0.2.1"), "192.0.2.1")
  }

  test("IPv6 は展開した先頭4ブロックを先頭ゼロなしの小文字で返す") {
    assertEquals(KeyIp.of("2001:0db8:85a3:0000:0000:8a2e:0370:7334"), "2001:db8:85a3:0")
  }

  test("後ろ4ブロックが違っても同じキーになる") {
    assertEquals(KeyIp.of("2001:db8:85a3:0:1111:2222:3333:4444"), KeyIp.of("2001:db8:85a3:0:ffff:ffff:ffff:ffff"))
    assertNotEquals(KeyIp.of("2001:db8:85a3:1::1"), KeyIp.of("2001:db8:85a3:2::1"))
  }

  test("圧縮表記を展開する") {
    assertEquals(KeyIp.of("2001:db8::1"), "2001:db8:0:0")
    assertEquals(KeyIp.of("2001:db8:1:2:3::9"), "2001:db8:1:2")
    assertEquals(KeyIp.of("1::"), "1:0:0:0")
  }

  test("::1 は 0:0:0:0") {
    assertEquals(KeyIp.of("::1"), "0:0:0:0")
    assertEquals(KeyIp.of("::"), "0:0:0:0")
  }

  test("大文字は小文字に正規化され、圧縮の有無で同じキーになる") {
    assertEquals(KeyIp.of("2001:DB8::ABCD"), "2001:db8:0:0")
    assertEquals(KeyIp.of("2001:DB8:0:0:0:0:0:ABCD"), KeyIp.of("2001:db8::abcd"))
  }

  test("解析できない表記は小文字にした文字列をそのまま返す") {
    assertEquals(KeyIp.of("::FFFF:1.2.3.4"), "::ffff:1.2.3.4")
    assertEquals(KeyIp.of("1::2::3"), "1::2::3")
    assertEquals(KeyIp.of("zz::1"), "zz::1")
    assertEquals(KeyIp.of("1:2:3"), "1:2:3")
  }

  test("空文字は空文字") {
    assertEquals(KeyIp.of(""), "")
  }
}
