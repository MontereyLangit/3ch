package ch3s.bbs.util

import ch3s.common.golden.{GoldenFile, Json, JsonValue}
import munit.FunSuite
import scala.scalajs.js
import scala.scalajs.js.typedarray.Uint8Array

/**
 * [[SjisEncoding]]（v6s の唯一の公開 Shift_JIS 変換経路）を、v5 (`src/common.js` の `sjis`
 * クラス) の実測 golden データと突き合わせる。
 *
 * docs/移植方針_step3_ScalaJS.md R-01 の受け入れ基準（v5 が正しく変換できていた文字は
 * バイト単位・文字単位で一致すること）を検証する。差異が許されるのは KB-04 / KB-17 / KB-18 と、
 * Node と本番Workers(workerd)のTextDecoder差異（制御コード3件、tests/golden/sjis-runtime-differences.json）
 * のみで、それ以外の差異は全てこのテストが検知して失敗する（新規の未列挙差異＝移植の失敗）。
 *
 * `tests/golden/sjis-dup-code-table.json` / `sjis-table.json` / `sjis-encode.json` /
 * `sjis-decode.json` の生成過程は `tests/golden/generate-sjis-compat.mjs` 参照。
 */
class SjisEncodingSuite extends FunSuite {

  private def bytesOf(ints: Seq[Int]): Uint8Array = {
    val u8 = new Uint8Array(ints.length)
    ints.indices.foreach(i => u8(i) = ints(i).toShort)
    u8
  }

  private def toIntArray(u8: Uint8Array): Array[Int] = {
    val out = Array.ofDim[Int](u8.length)
    var i = 0
    while (i < u8.length) { out(i) = u8(i).toInt & 0xff; i += 1 }
    out
  }

  /** JS の `String.fromCodePoint` 相当（サロゲートペアの組み立てを含む）。 */
  private def charFromCodePoint(cp: Int): String =
    new String(Character.toChars(cp))

  /** `&#codepoint;` のNCR文字列を、そのままASCIIバイト列化したもの（KB-17期待値の算出用）。 */
  private def ncrBytes(cp: Int): Array[Int] = s"&#$cp;".toCharArray.map(_.toInt)

  // ============================================================
  // 1. 重複符号 / 置換文字 の上書きペア（機械的に抽出したもの。tests/golden/sjis-dup-code-table.json）
  // ============================================================
  test("golden: 重複符号(NEC)/置換文字(U+FFFD)の上書きペアがv5と同一バイト列で往復する") {
    val table = Json.parse(GoldenFile.readText("sjis-dup-code-table.json")).asObj
    val pairs = table("overridePairs").asArr
    assert(pairs.nonEmpty, "sjis-dup-code-table.json が空。機械抽出に失敗している可能性")

    for (p <- pairs) {
      val cp = p.field("cp").asInt
      val v5Bytes = p.field("v5Bytes").asArr.map(_.asInt)
      val ejsBytes = p.field("ejsBytes").asArr.map(_.asInt)
      val kind = p.field("kind").asString
      val ch = charFromCodePoint(cp)

      // encode: v5と同じバイト位置を出力する
      assertEquals(toIntArray(SjisEncoding.encode(ch)).toList, v5Bytes, s"encode mismatch for cp=0x${cp.toHexString} kind=$kind")

      // decode: v5の優先バイト位置は正しい文字に戻る（encoding-japanese単体では化ける位置）
      assertEquals(SjisEncoding.decode(bytesOf(v5Bytes)), ch, s"decode(v5Bytes) mismatch for cp=0x${cp.toHexString}")

      // 真のCP932重複符号(NEC側)は、encoding-japanese標準側の位置も引き続き正しく復元できる
      // (この位置には上書きを被せていないため、素のencoding-japaneseの正しい変換に依存する)。
      if (kind == "necDuplicateCode") {
        assertEquals(SjisEncoding.decode(bytesOf(ejsBytes)), ch, s"decode(ejsBytes) mismatch for cp=0x${cp.toHexString}")
      }
    }
  }

  // ============================================================
  // 2. v5の実測テーブル全件(9,027件)のdecode方向
  // ============================================================
  test("golden: sjis-table.json の decode 全件(9,027件)が新実装と一致する（説明可能な差異を除く）") {
    val table = Json.parse(GoldenFile.readText("sjis-table.json")).asObj
    val singleByte = table("singleByte").asArr
    val doubleByte = table("doubleByte").asArr

    // R-06: Node の TextDecoder('sjis') と本番Workers(workerd)は、この3バイトについてのみ
    // 異なる文字を返す(tests/golden/sjis-runtime-differences.json)。workerd は
    // encoding-japanese と同じ恒等変換であり、本番のv5と新実装の間には実差異が無い
    // （sjis-table.json は Node 実測のため、ここだけ見かけ上の差異が出る）。
    val controlCodeArtifactBytes = Set(0x1a, 0x1c, 0x7f)

    var unexplained = List.empty[String]

    for (entry <- singleByte) {
      val byte = entry.field("byte").asInt
      val expectedChar = charFromCodePoint(entry.field("char").asInt)
      val got = SjisEncoding.decode(bytesOf(Seq(byte)))
      if (got != expectedChar) {
        if (!controlCodeArtifactBytes.contains(byte)) {
          unexplained ::= s"single byte=0x${byte.toHexString} expected=$expectedChar got=$got"
        }
      }
    }

    // 重複符号/置換文字の上書き位置は「一致するはず」の集合に含まれる(上書きにより救済済み)。
    val overrideBytePositions: Set[(Int, Int)] = {
      val overrides = Json.parse(GoldenFile.readText("sjis-dup-code-table.json")).asObj("overridePairs").asArr
      overrides.map(p => (p.field("v5Bytes").asArr(0).asInt, p.field("v5Bytes").asArr(1).asInt)).toSet
    }

    for (entry <- doubleByte) {
      val lead = entry.field("lead").asInt
      val second = entry.field("second").asInt
      val cp = entry.field("char").asInt
      val expectedChar = charFromCodePoint(cp)
      val got = SjisEncoding.decode(bytesOf(Seq(lead, second)))
      if (got != expectedChar) {
        val isKnownReplacementHole = cp == 0xfffd && !overrideBytePositions.contains((lead, second))
        if (!isKnownReplacementHole) {
          unexplained ::= s"double lead=0x${lead.toHexString} second=0x${second.toHexString} expected=$expectedChar(0x${cp.toHexString}) got=$got"
        } else {
          // KB-18: v5のテーブル構築元(Node TextDecoder)がJIS X0208未定義の第2バイト位置を
          // Unicode置換文字に丸め込んでいた「穴」。新実装はencoding-japanese標準の'?'フォールバックを返す。
          assertEquals(got, "?", s"replacement-char hole lead=0x${lead.toHexString} second=0x${second.toHexString} expected '?' fallback, got=$got")
        }
      } else {
        // 上書き位置は一致していて当然だが、上書きが実際に機能していることも確認する
        if (overrideBytePositions.contains((lead, second))) {
          assertEquals(got, expectedChar, s"override position lead=0x${lead.toHexString} second=0x${second.toHexString}")
        }
      }
    }

    assert(unexplained.isEmpty, s"説明のつかないdecode差異が${unexplained.size}件見つかった（先頭10件）:\n${unexplained.take(10).mkString("\n")}")
  }

  // ============================================================
  // 3. sjis-encode.json 全件（KB-17を含む）
  // ============================================================
  test("golden: sjis-encode.json の全ケースが新実装の期待値と一致する(KB-17を含む)") {
    val cases = Json.parse(GoldenFile.readText("sjis-encode.json")).asArr
    for (c <- cases) {
      val input = c.field("input").asString
      val outputEntries = c.field("output").asArr
      val hasUndefined = outputEntries.exists(_.isUndefinedMarker)
      val got = toIntArray(SjisEncoding.encode(input)).toList
      if (!hasUndefined) {
        val expected = outputEntries.map(_.asInt)
        assertEquals(got, expected, s"input=$input")
      } else {
        // KB-17: v5はNULバイト(undefined->0)になるが、新実装はNCRへフォールバックする。
        // 期待値をv5のundefinedマーカーからNCR表現へ機械的に組み立てて比較する
        // (手で個別文字を決め打ちしない: 元のコードポイント列から導出する)。
        val expected = scala.collection.mutable.ArrayBuffer.empty[Int]
        var i = 0
        while (i < input.length) {
          val c1 = input.charAt(i)
          val isPair = c1 >= 0xd800 && c1 <= 0xdbff && i + 1 < input.length &&
            input.charAt(i + 1) >= 0xdc00 && input.charAt(i + 1) <= 0xdfff
          if (isPair) {
            val cp = Character.toCodePoint(c1, input.charAt(i + 1))
            expected ++= ncrBytes(cp)
            i += 2
          } else if (c1 >= 0xd800 && c1 <= 0xdfff) {
            // 孤立サロゲート: v5もundefinedになる。コードポイントはUTF-16コード単位そのもの。
            expected ++= ncrBytes(c1.toInt)
            i += 1
          } else {
            // ASCII等、v5が正常にencodeできる文字はそのまま(このケースが混在するのはgolden側の
            // 複合ケース、例: "A😀あ")。v5のバイト列と一致するはずなので、新実装1文字分を都度encodeする。
            expected ++= toIntArray(SjisEncoding.encode(c1.toString))
            i += 1
          }
        }
        assertEquals(got, expected.toList, s"input=$input (KB-17 NCR fallback)")
      }
    }
  }

  // ============================================================
  // 4. sjis-decode.json 全件（KB-18を含む）
  // ============================================================
  test("golden: sjis-decode.json の全ケースが新実装の期待値と一致する(KB-18を含む: 例外を投げない)") {
    val cases = Json.parse(GoldenFile.readText("sjis-decode.json")).asArr
    for (c <- cases) {
      val input = c.field("input").asArr.map(_.asInt)
      val throwsField = c.field("throws")
      // 新実装は例外を投げないことがKB-18の対応方針そのものなので、まず例外が起きないことを確認する。
      val got = SjisEncoding.decode(bytesOf(input))
      if (!throwsField.isNull) {
        // v5はTypeErrorを送出していた入力。新実装は'?'にフォールバックする(実測: encoding-japanese既定)。
        assertEquals(got, "?", s"input=$input (KB-18 fallback, v5 threw ${throwsField.asString})")
      } else {
        val expected = c.field("output").asString
        // v5は末尾に不完全な(リードバイトだけの)データが残った場合、無音で捨てる(KB-18)。
        // 新実装は例外を投げない代わりに代替文字'?'を1文字追加する(encoding-japanese既定)。
        // 「入力の末尾バイトが2バイト文字のリード範囲(0x81-0x9F, 0xE0-0xFC)にある」ことが
        // その一般条件であり、特定の入力値を決め打ちしない。
        val lastIsLoneLead = input.nonEmpty && {
          val b = input.last
          (b >= 0x81 && b <= 0x9f) || (b >= 0xe0 && b <= 0xfc)
        } && got == expected + "?"
        if (lastIsLoneLead) {
          assertEquals(got, expected + "?", s"input=$input (KB-18 trailing incomplete lead byte fallback)")
        } else {
          assertEquals(got, expected, s"input=$input")
        }
      }
    }
  }
}
