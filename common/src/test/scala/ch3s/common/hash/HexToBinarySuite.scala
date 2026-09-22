package ch3s.common.hash

import ch3s.common.golden.{GoldenFile, Json}
import munit.FunSuite

/** `tests/golden/hex-to-binary.json` を読み、`src/common.js` の `hexToBinary()` と一致することを確認する。 */
class HexToBinarySuite extends FunSuite {

  test("golden: hex-to-binary.json の全ケースがJS実測値と一致する(奇数長の切り捨てを含む)") {
    val cases = Json.parse(GoldenFile.readText("hex-to-binary.json")).asArr
    for (c <- cases) {
      val input = c.field("input").asString
      val expectedCodes = c.field("outputCharCodes").asArr.map(_.asInt)
      val got = HexToBinary.hexToBinary(input)
      assertEquals(got.map(_.toInt).toList, expectedCodes, s"input=$input")
    }
  }
}
