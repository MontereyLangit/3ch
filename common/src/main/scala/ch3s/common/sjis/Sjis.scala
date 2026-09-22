package ch3s.common.sjis

import scala.collection.mutable

/**
 * `src/common.js` の `sjis` クラス（Shift_JIS エンコード/デコード）の Scala 3 移植。
 *
 * テーブル本体は [[SjisTableData]] に埋め込まれた実測データを使う（このファイルでは
 * `TextDecoder` 相当のロジックは実装しない。理由は SjisTableData のコメント参照）。
 * このファイルが担うのは、テーブルを組み立てる手順と、encode/decode の状態遷移
 * ロジック（KB-17 / KB-18 を含む）を JS の実装と一致させることだけ。
 *
 * KB-17: `encode()` はテーブルに存在しない文字（絵文字等）に対して JS の `undefined` を
 * 返す。Scala側では `None` として表現する（`Some`/`None` の `Option[Int]` で明示）。
 * 呼び出し側で `Uint8Array.from` に相当する変換を行う場合は、`None` を `0`（NUL バイト）
 * に読み替えること（JS の `Uint8Array.from([undefined])` が `0` になるのと同じ変換を、
 * 呼び出し側で明示的に行う設計とした）。
 *
 * KB-18: `decode()` は未知のリードバイト・未知の第2バイトに対して JS の `TypeError` を
 * 送出する。Scala/JVM に `TypeError` は存在しないため、[[SjisDecodeError]]
 * （`RuntimeException` のサブクラス）を代わりに投げる。末尾の不完全な1バイトは
 * 例外を投げずに無音で捨てる（JS の `reduce` がループを終えるだけの挙動と同じ）。
 */
final class Sjis {
  import Sjis.{singleByteTable, leadByteTable, encodeTable}

  /**
   * 文字列を Shift_JIS バイト列へ変換する。
   * 戻り値の各要素は 1 バイト分の値（0-255）。テーブルに存在しない文字（コード単位、または
   * 孤立サロゲート）に対しては `None`（JS の `undefined` 相当、KB-17）を1要素だけ積む。
   * 入力は JS の `[...str]`（文字列のイテレータ、サロゲートペアを1要素として扱う）と
   * 同じ単位で走査する。
   */
  def encode(str: String): Array[Option[Int]] = {
    val out = mutable.ArrayBuffer.empty[Option[Int]]
    for (cp <- Sjis.iterateJsStringElements(str)) {
      encodeTable.get(cp) match {
        case Some(bytes) => bytes.foreach(b => out += Some(b))
        case None        => out += None
      }
    }
    out.toArray
  }

  /**
   * Shift_JIS バイト列を文字列へ復元する。
   * 未知のリードバイト・未知の第2バイトに対しては [[SjisDecodeError]] を投げる（KB-18）。
   * 末尾に不完全な（リードバイトだけの）データが残った場合は無音で捨てる（KB-18）。
   */
  def decode(bytes: Seq[Int]): String = {
    val sb = new StringBuilder
    var pendingLead: Int = -1 // -1 = 先頭バイト待ち（this.d のルート相当）
    for (b <- bytes) {
      if (pendingLead < 0) {
        singleByteTable.get(b) match {
          case Some(ch) => sb.append(ch)
          case None =>
            leadByteTable.get(b) match {
              case Some(_) => pendingLead = b
              case None =>
                throw new SjisDecodeError(
                  s"sjis.decode: 未知のリードバイト 0x${b.toHexString} (KB-18)"
                )
            }
        }
      } else {
        val secondMap = leadByteTable(pendingLead) // pendingLeadは設定時に存在確認済み
        secondMap.get(b) match {
          case Some(ch) =>
            sb.append(ch)
            pendingLead = -1
          case None =>
            throw new SjisDecodeError(
              s"sjis.decode: リードバイト 0x${pendingLead.toHexString} に対する未知の第2バイト 0x${b.toHexString} (KB-18)"
            )
        }
      }
    }
    // ループ終了時に pendingLead が残っていても例外は投げず、無音で捨てる（KB-18）。
    sb.toString
  }
}

/** KB-18: JS の `TypeError` に相当する例外（JVM/JSの両方で共通に扱える独自例外）。 */
final class SjisDecodeError(message: String) extends RuntimeException(message)

object Sjis {

  /** byte(2桁hex) + codePoint(4桁hex) の6桁固定長レコードをデコードする。 */
  private def parseSingleByteTable(hex: String): mutable.LinkedHashMap[Int, Char] = {
    val m = mutable.LinkedHashMap.empty[Int, Char]
    var i = 0
    while (i < hex.length) {
      val byte = Integer.parseInt(hex.substring(i, i + 2), 16)
      val cp = Integer.parseInt(hex.substring(i + 2, i + 6), 16)
      m(byte) = cp.toChar
      i += 6
    }
    m
  }

  /** leadByte(2桁) + secondByte(2桁) + codePoint(4桁) の8桁固定長レコードをデコードする。 */
  private def parseDoubleByteTable(hex: String): mutable.LinkedHashMap[Int, mutable.LinkedHashMap[Int, Char]] = {
    val m = mutable.LinkedHashMap.empty[Int, mutable.LinkedHashMap[Int, Char]]
    var i = 0
    while (i < hex.length) {
      val lead = Integer.parseInt(hex.substring(i, i + 2), 16)
      val second = Integer.parseInt(hex.substring(i + 2, i + 4), 16)
      val cp = Integer.parseInt(hex.substring(i + 4, i + 8), 16)
      val inner = m.getOrElseUpdate(lead, mutable.LinkedHashMap.empty[Int, Char])
      inner(second) = cp.toChar
      i += 8
    }
    m
  }

  /** this.d 相当: 単バイト側（葉）。 */
  private[sjis] val singleByteTable: Map[Int, Char] =
    parseSingleByteTable(SjisTableData.singleByteHex).toMap

  /** this.d 相当: リードバイト側（枝）。 */
  private[sjis] val leadByteTable: Map[Int, Map[Int, Char]] =
    parseDoubleByteTable(SjisTableData.doubleByteHex).view.mapValues(_.toMap).toMap

  /**
   * this.e 相当（文字 → バイト列）。JSコンストラクタと同じ順序（単バイト昇順 → 2バイトは
   * リードバイト昇順・第2バイト昇順）で走査し、後から出てきたエントリで上書きする
   * （同じ文字に複数のバイト列が対応する場合、JSの `this.e[_] = w` 代入と同じ「後勝ち」になる）。
   */
  private[sjis] val encodeTable: Map[Int, Array[Int]] = {
    val m = mutable.LinkedHashMap.empty[Int, Array[Int]]
    for ((byte, ch) <- parseSingleByteTable(SjisTableData.singleByteHex)) {
      m(ch.toInt) = Array(byte)
    }
    val doubleHex = SjisTableData.doubleByteHex
    var i = 0
    while (i < doubleHex.length) {
      val lead = Integer.parseInt(doubleHex.substring(i, i + 2), 16)
      val second = Integer.parseInt(doubleHex.substring(i + 2, i + 4), 16)
      val cp = Integer.parseInt(doubleHex.substring(i + 4, i + 8), 16)
      m(cp) = Array(lead, second)
      i += 8
    }
    m.toMap
  }

  /**
   * JS の `[...str]`（文字列イテレータ）と同じ単位で文字列を走査する。
   * 正しいサロゲートペアは1つのコードポイントとして扱い、孤立サロゲートは
   * そのUTF-16コード単位の値そのものを1要素として返す（UTF-8変換時のU+FFFD代替とは異なり、
   * ここでは代替を行わない点に注意。JSの文字列イテレータ自体は代替を行わないため）。
   */
  private[sjis] def iterateJsStringElements(s: String): Array[Int] = {
    val out = mutable.ArrayBuffer.empty[Int]
    var i = 0
    val n = s.length
    while (i < n) {
      val c1 = s.charAt(i).toInt
      if (c1 >= 0xd800 && c1 <= 0xdbff && i + 1 < n) {
        val c2 = s.charAt(i + 1).toInt
        if (c2 >= 0xdc00 && c2 <= 0xdfff) {
          out += (((c1 - 0xd800) << 10) + (c2 - 0xdc00) + 0x10000)
          i += 2
        } else {
          out += c1
          i += 1
        }
      } else {
        out += c1
        i += 1
      }
    }
    out.toArray
  }
}
