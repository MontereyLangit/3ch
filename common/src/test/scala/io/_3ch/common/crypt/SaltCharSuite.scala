package io._3ch.common.crypt

import io._3ch.common.golden.{GoldenFile, Json}
import munit.FunSuite

/**
 * `tests/golden/translate-salt-char.json`（0x00-0xFF全数）を読み、
 * `src/common.js` の `translateSaltChar()` の KB-19 のバグ（演算子優先順位・テーブル取り違え）を
 * Scala版がそのまま再現していることを確認する。
 */
class SaltCharSuite extends FunSuite {

  test("golden: translate-salt-char.json の0x00-0xFF全件がJS実測値と一致する(KB-19)") {
    val cases = Json.parse(GoldenFile.readText("translate-salt-char.json")).asArr
    for (c <- cases) {
      val code = c.field("inputCharCode").asInt
      val outField = c.field("output")
      val expected: Option[Char] =
        if (outField.isUndefinedMarker) None else Some(outField.field("charCode").asInt.toChar)
      assertEquals(SaltChar.translateSaltChar(code.toChar), expected, s"code=0x${code.toHexString}")
    }
  }
}
