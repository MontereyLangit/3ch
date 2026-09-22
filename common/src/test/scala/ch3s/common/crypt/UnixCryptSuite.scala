package ch3s.common.crypt

import ch3s.common.golden.{GoldenFile, Json}
import munit.FunSuite

/**
 * `tests/golden/crypt.json` を読み、`src/common.js` の Unix crypt(3) DES 実装と
 * Scala版 [[UnixCrypt]] の出力が一致することを確認する
 * （8バイト超のパスワードの切り詰め、ソルト文字の内部補正、2文字未満のソルトの実測挙動を含む）。
 */
class UnixCryptSuite extends FunSuite {

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
