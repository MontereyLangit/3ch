package ch3s.bbs.util

import ch3s.common.golden.{GoldenFile, Json, JsonValue}
import munit.FunSuite
import scala.scalajs.js.typedarray.Uint8Array

/**
 * [[SjisEncoding]]（v6s の唯一の公開 Shift_JIS 変換経路）を、v5 (`src/common.js` の `sjis`
 * クラス) の実測 golden データと突き合わせる。
 *
 * docs/移植方針_step3_ScalaJS.md R-01（**`encoding-japanese`案Aを破棄し、v5と同じ構築手順へ
 * 戻した最終決定**）の受け入れ基準を検証する: v5が正しく変換できていた文字はバイト単位・
 * 文字単位で完全一致すること。差異が許されるのは KB-17（encode: NCRフォールバック）と
 * KB-18（decode: `?`フォールバック）のみで、それ以外はこのテストが検知して失敗する
 * （未列挙の差異＝移植の失敗）。
 *
 * 本テストは2種類の検証を行う:
 *   1. `tests/golden/sjis-table.json`（v5実表 9,027件）・`sjis-encode.json`・`sjis-decode.json`
 *      に対する golden 突き合わせ（v5実測データそのものとの一致）。
 *   2. golden データから独立に構築した参照実装（[[referenceEncode]] / [[referenceDecode]]。
 *      本ファイル内でゼロから再構築するミラーであり、[[SjisEncoding]] 本体のコードは一切
 *      呼ばない）に対する BMP 全域(65,536件)の encode 往復・1/2バイト全数(65,536件)の decode
 *      スイープ。[[SjisEncoding]] の実装（テーブル構築・状態遷移）そのものにバグが無いことを、
 *      golden データに基づく独立実装との突き合わせで確認する。
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

  private def charFromCodePoint(cp: Int): String = new String(Character.toChars(cp))
  private def ncrBytes(cp: Int): Array[Int] = s"&#$cp;".toCharArray.map(_.toInt)

  // ============================================================
  // 0. golden データ (tests/golden/sjis-table.json) から独立に構築した参照実装。
  //    [[SjisEncoding]] 本体のコードは一切参照しない。
  // ============================================================
  private val goldenTable = Json.parse(GoldenFile.readText("sjis-table.json")).asObj

  /** v5実表の単バイト側（葉）。`this.d` 相当。 */
  private val refSingle: Map[Int, Char] =
    goldenTable("singleByte").asArr.map { e =>
      e.field("byte").asInt -> charFromCodePoint(e.field("char").asInt).charAt(0)
    }.toMap

  /** v5実表のリードバイト側（枝）。`this.d` 相当。 */
  private val refLead: Map[Int, Map[Int, Char]] =
    goldenTable("doubleByte").asArr
      .groupBy(_.field("lead").asInt)
      .view
      .mapValues(_.map(e => e.field("second").asInt -> charFromCodePoint(e.field("char").asInt).charAt(0)).toMap)
      .toMap

  /**
   * v5実表の encode 表（`this.e` 相当）。golden データの並び順（単バイト昇順 → 2バイトは
   * リードバイト昇順・第2バイト昇順、`sjis-table.json` の `note` に明記されている構築順）で
   * 走査し、後から出てきたエントリで上書きする（同じ文字に複数のバイト列が対応する場合、
   * JS の `this.e[_] = w` 代入と同じ「後勝ち」になる。CP932重複符号の採用側はこれで決まる）。
   */
  private val refEncodeTable: Map[Int, Array[Int]] = {
    val m = scala.collection.mutable.LinkedHashMap.empty[Int, Array[Int]]
    for (e <- goldenTable("singleByte").asArr) m(e.field("char").asInt) = Array(e.field("byte").asInt)
    for (e <- goldenTable("doubleByte").asArr) m(e.field("char").asInt) = Array(e.field("lead").asInt, e.field("second").asInt)
    m.toMap
  }

  /** KB-17: v5に存在しない文字はNCR（v5のNULバイトの代わり）。それ以外はv5と同一バイト列。 */
  private def referenceEncode(cp: Int): Array[Int] = refEncodeTable.getOrElse(cp, ncrBytes(cp))

  /**
   * KB-18: v5が`TypeError`を送出する（未知のリードバイト・未知の第2バイト）箇所、および
   * v5が無音で捨てる（末尾の不完全なリードバイト）箇所を、`?`へのフォールバックに置き換える。
   * それ以外はv5実表と同一の文字を返す。[[SjisEncoding.decode]] と同じ状態遷移を、golden
   * データのみから独立に再構築したもの。
   */
  private def referenceDecode(bytes: Array[Int]): String = {
    val sb = new StringBuilder
    var i = 0
    while (i < bytes.length) {
      val b = bytes(i) & 0xff
      refSingle.get(b) match {
        case Some(ch) => sb.append(ch); i += 1
        case None =>
          refLead.get(b) match {
            case Some(branch) =>
              if (i + 1 < bytes.length) {
                val second = bytes(i + 1) & 0xff
                branch.get(second) match {
                  case Some(ch) => sb.append(ch)
                  case None     => sb.append('?')
                }
                i += 2
              } else {
                sb.append('?')
                i += 1
              }
            case None =>
              sb.append('?')
              i += 1
          }
      }
    }
    sb.toString
  }

  // ============================================================
  // 1. v5実表(sjis-table.json、9,027件)のdecodeが完全一致する（例外なし）
  // ============================================================
  test("golden: sjis-table.json の decode 全件(9,027件)がv5と完全一致する") {
    var checked = 0
    for (e <- goldenTable("singleByte").asArr) {
      val byte = e.field("byte").asInt
      val expected = charFromCodePoint(e.field("char").asInt)
      assertEquals(SjisEncoding.decode(bytesOf(Seq(byte))), expected, s"single byte=0x${byte.toHexString}")
      checked += 1
    }
    for (e <- goldenTable("doubleByte").asArr) {
      val lead = e.field("lead").asInt
      val second = e.field("second").asInt
      val expected = charFromCodePoint(e.field("char").asInt)
      assertEquals(SjisEncoding.decode(bytesOf(Seq(lead, second))), expected, s"double lead=0x${lead.toHexString} second=0x${second.toHexString}")
      checked += 1
    }
    assertEquals(checked, 9027, "sjis-table.json の件数が変わっている(想定9,027件)")
  }

  // ============================================================
  // 2. sjis-encode.json 全件（KB-17を含む）
  // ============================================================
  test("golden: sjis-encode.json の全ケースがv5と一致する(KB-17: NCRフォールバックを含む)") {
    val cases = Json.parse(GoldenFile.readText("sjis-encode.json")).asArr
    for (c <- cases) {
      val input = c.field("input").asString
      val outputEntries = c.field("output").asArr
      val hasUndefined = outputEntries.exists(_.isUndefinedMarker)
      val got = toIntArray(SjisEncoding.encode(input)).toList
      if (!hasUndefined) {
        assertEquals(got, outputEntries.map(_.asInt), s"input=$input")
      } else {
        // KB-17: v5はNULバイト(undefined->0)になるが、新実装はNCRへフォールバックする。
        // 期待値は入力のコードポイント列から機械的に組み立てる(手で決め打ちしない)。
        val expected = scala.collection.mutable.ArrayBuffer.empty[Int]
        var i = 0
        while (i < input.length) {
          val c1 = input.charAt(i)
          val isPair = c1 >= 0xd800 && c1 <= 0xdbff && i + 1 < input.length &&
            input.charAt(i + 1) >= 0xdc00 && input.charAt(i + 1) <= 0xdfff
          if (isPair) {
            val cp = Character.toCodePoint(c1, input.charAt(i + 1))
            expected ++= referenceEncode(cp)
            i += 2
          } else {
            expected ++= referenceEncode(c1.toInt)
            i += 1
          }
        }
        assertEquals(got, expected.toList, s"input=$input (KB-17 NCR fallback)")
      }
    }
  }

  // ============================================================
  // 3. sjis-decode.json 全件（KB-18を含む）
  // ============================================================
  test("golden: sjis-decode.json の全ケースが例外を投げずv5と一致する(KB-18を含む)") {
    val cases = Json.parse(GoldenFile.readText("sjis-decode.json")).asArr
    for (c <- cases) {
      val input = c.field("input").asArr.map(_.asInt)
      val got = SjisEncoding.decode(bytesOf(input)) // 例外を投げないこと自体がKB-18の対応方針
      val throwsField = c.field("throws")
      // どちらのケースも、期待値は referenceDecode(本ファイル冒頭、golden データのみから
      // 独立に構築したKB-18対応込みの参照実装)と一致するはずである。
      assertEquals(got, referenceDecode(input.toArray), s"input=$input (throws=${if (throwsField.isNull) "null" else throwsField.asString})")
      if (throwsField.isNull) {
        val expected = c.field("output").asString
        if (got == expected) () // KB-18は関与しない(v5自身がそのまま変換できた入力)
        else
          // KB-18: v5は末尾に不完全な(リードバイトだけの)データを無音で捨てる。新実装は
          // v5が実際に変換できたところまでは完全に温存し、捨てられた末尾だけを`?`で埋める
          // (すり替えではなく追記であることを保証する)。
          assert(
            got.startsWith(expected),
            s"input=$input: KB-18フォールバックはv5が変換できた部分を完全に温存すべき (got=$got expected=$expected)",
          )
      }
    }
  }

  // ============================================================
  // 4. BMP全域(65,536件)の encode 往復 — golden由来の参照実装との突き合わせ
  //
  // referenceEncode は KB-17(NCRフォールバック)のルールを内包した「あるべき結果」なので、
  // ここでの一致要求は例外なし(100%)。[[SjisEncoding]] 自体の実装(テーブル構築・走査)に
  // バグが無いことの確認であり、v5との差異の話ではない(それは項目2で別途検証済み)。
  // ============================================================
  test("mirror: BMP全域(U+0000-U+FFFF)のencodeが参照実装と完全一致する") {
    val unexpected = scala.collection.mutable.ArrayBuffer.empty[String]
    var cp = 0
    while (cp <= 0xffff) {
      val text = charFromCodePoint(cp)
      val got = toIntArray(SjisEncoding.encode(text))
      val expected = referenceEncode(cp)
      if (!got.sameElements(expected) && unexpected.size < 20) {
        unexpected += s"cp=0x${cp.toHexString} got=${got.toList} expected=${expected.toList}"
      }
      cp += 1
    }
    assert(unexpected.isEmpty, s"参照実装との差異が見つかった(実装バグの可能性):\n${unexpected.mkString("\n")}")
  }

  // ============================================================
  // 5. 1/2バイト全数(65,536件)の decode スイープ — golden由来の参照実装との突き合わせ
  //
  // referenceDecode は KB-18(フォールバック)のルールを内包した「あるべき結果」なので、
  // ここでも一致要求は例外なし(100%)。
  // ============================================================
  test("mirror: 1/2バイト全数スイープのdecodeが参照実装と完全一致する") {
    val unexpected = scala.collection.mutable.ArrayBuffer.empty[String]

    var b = 0
    while (b <= 0xff) {
      val bytes = Array(b)
      val got = SjisEncoding.decode(bytesOf(bytes.toIndexedSeq))
      val expected = referenceDecode(bytes)
      if (got != expected && unexpected.size < 20) unexpected += s"byte=0x${b.toHexString} got=$got expected=$expected"
      b += 1
    }

    var pair = 0
    while (pair <= 0xffff) {
      val lead = pair >> 8
      val second = pair & 0xff
      val bytes = Array(lead, second)
      val got = SjisEncoding.decode(bytesOf(bytes.toIndexedSeq))
      val expected = referenceDecode(bytes)
      if (got != expected && unexpected.size < 20) unexpected += s"lead=0x${lead.toHexString} second=0x${second.toHexString} got=$got expected=$expected"
      pair += 1
    }

    assert(unexpected.isEmpty, s"参照実装との差異が見つかった(実装バグの可能性):\n${unexpected.mkString("\n")}")
  }

  // ============================================================
  // 6. 補助面(サロゲートペア)・孤立サロゲートのencode — 全てKB-17(NCR)になる
  // ============================================================
  test("golden: 絵文字等の補助面文字・孤立サロゲートは全てNCRへフォールバックする(KB-17)") {
    val samples = List(0x1f600, 0x1d400, 0x20000, 0x2f800, 0xe000, 0xfffd)
    for (cp <- samples) {
      val text = charFromCodePoint(cp)
      assertEquals(toIntArray(SjisEncoding.encode(text)).toList, referenceEncode(cp).toList, s"cp=0x${cp.toHexString}")
    }
    // 孤立サロゲート: 対になっていないUTF-16コード単位そのものがコードポイント扱いになる。
    val loneHigh = "\ud800A"
    val loneLow = "A\udfff"
    assertEquals(
      toIntArray(SjisEncoding.encode(loneHigh)).toList,
      (referenceEncode(0xd800) ++ referenceEncode('A'.toInt)).toList,
    )
    assertEquals(
      toIntArray(SjisEncoding.encode(loneLow)).toList,
      (referenceEncode('A'.toInt) ++ referenceEncode(0xdfff)).toList,
    )
  }
}
