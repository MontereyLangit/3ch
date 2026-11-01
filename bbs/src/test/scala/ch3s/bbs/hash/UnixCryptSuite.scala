package ch3s.bbs.hash

import ch3s.common.golden.{GoldenFile, Json}
import munit.FunSuite

/**
 * [[UnixCrypt]] を `tests/golden/crypt.json` / `crypt-bytes.json`（v5 `src/common.js` の crypt(3) 実測値）と照合する
 * （8バイト超のパスワードの切り詰め、ソルト文字の内部補正、2文字未満のソルトの実測挙動を含む）。
 */
class UnixCryptSuite extends FunSuite {

  test("golden: cryptBytes preserves undefined salt elements (KB-26)") {
    for (c <- Json.parse(GoldenFile.readText("crypt-bytes.json")).asArr) {
      val expected = c.field("output").asArr.map(v => if (v.isUndefinedMarker) None else Some(v.asInt))
      assertEquals(UnixCrypt.cryptBytes(c.field("password").asString, c.field("salt").asString), expected)
    }
  }

  test("golden: crypt.json の全ケースがJS実測値と一致する") {
    val cases = Json.parse(GoldenFile.readText("crypt.json")).asArr
    for (c <- cases) {
      val password = c.field("password").asString
      val salt = c.field("salt").asString
      val expected = c.field("output").asString
      assertEquals(UnixCrypt.crypt(password, salt), expected, s"password=$password salt=$salt")
    }
  }

  test("8バイトを超えるパスワードは先頭8バイトが同じなら同一の結果になる(切り詰め)") {
    assertEquals(
      UnixCrypt.crypt("12345678", "ab"),
      UnixCrypt.crypt("12345678extra-tail-ignored", "ab")
    )
  }
}
