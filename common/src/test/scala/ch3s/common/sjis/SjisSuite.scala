package ch3s.common.sjis

import ch3s.common.golden.{GoldenFile, Json, JsonValue}
import munit.FunSuite

/**
 * `tests/golden/sjis-table.json` / `sjis-encode.json` / `sjis-decode.json` を読み、
 * `src/common.js` の `sjis` クラスの実測結果と Scala 版 [[Sjis]] の出力が一致することを確認する。
 */
class SjisSuite extends FunSuite {

  private def toOptInt(v: JsonValue): Option[Int] =
    if (v.isUndefinedMarker) None else Some(v.asInt)

  test("golden: sjis-table.json のレコード数と本番実装(SjisTableData由来のテーブル)の件数が一致する") {
    val table = Json.parse(GoldenFile.readText("sjis-table.json")).asObj
    val singleByte = table("singleByte").asArr
    val doubleByte = table("doubleByte").asArr

    assertEquals(Sjis.singleByteTable.size, singleByte.length)
    assertEquals(Sjis.leadByteTable.values.map(_.size).sum, doubleByte.length)

    // 個々のレコードも全件突き合わせる(KB-04: NEC特殊文字・IBM拡張が含まれること/
    // KB-18: 0xF0-0xFC のリードバイトがテーブルに存在しないことを含めて実データで検証する)。
    for (entry <- singleByte) {
      val byte = entry.field("byte").asInt
      val char = entry.field("char").asInt.toChar
      assertEquals(Sjis.singleByteTable.get(byte), Some(char), s"byte=0x${byte.toHexString}")
    }
    for (entry <- doubleByte) {
      val lead = entry.field("lead").asInt
      val second = entry.field("second").asInt
      val char = entry.field("char").asInt.toChar
      val got = Sjis.leadByteTable.get(lead).flatMap(_.get(second))
      assertEquals(got, Some(char), s"lead=0x${lead.toHexString} second=0x${second.toHexString}")
    }
  }

  test("golden: sjis-encode.json の全ケースがJS実測値と一致する(KB-04/KB-17を含む)") {
    val cases = Json.parse(GoldenFile.readText("sjis-encode.json")).asArr
    val s = new Sjis
    for (c <- cases) {
      val input = c.field("input").asString
      val expected = c.field("output").asArr.map(toOptInt)
      assertEquals(s.encode(input).toList, expected, s"input=$input")
    }
  }

  test("golden: sjis-decode.json の全ケースがJS実測値と一致する(KB-04/KB-18を含む)") {
    val cases = Json.parse(GoldenFile.readText("sjis-decode.json")).asArr
    val s = new Sjis
    for (c <- cases) {
      val input = c.field("input").asArr.map(_.asInt)
      val throwsField = c.field("throws")
      if (!throwsField.isNull) {
        intercept[SjisDecodeError] {
          s.decode(input)
        }
      } else {
        val expected = c.field("output").asString
        assertEquals(s.decode(input), expected, s"input=$input")
      }
    }
  }
}
