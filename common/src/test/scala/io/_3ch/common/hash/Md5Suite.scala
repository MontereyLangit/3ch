package io._3ch.common.hash

import io._3ch.common.golden.{GoldenFile, Json}
import munit.FunSuite

/** `tests/golden/md5.json` を読み、`src/common.js` の `md5()` と一致することを確認する。 */
class Md5Suite extends FunSuite {

  test("golden: md5.json の全ケースがJS実測値と一致する(ブロック境界・孤立サロゲートを含む)") {
    val cases = Json.parse(GoldenFile.readText("md5.json")).asArr
    for (c <- cases) {
      val input = c.field("input").asString
      val expected = c.field("output").asString
      assertEquals(Md5.md5(input), expected, s"input=${input.take(20)}(len=${input.length})")
    }
  }
}
