package ch3s.bbs.util

import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal
import scala.scalajs.js.typedarray.Uint8Array

/**
 * Shift_JIS 変換。v5 `src/common.js` の `sjis` と同じ手順でテーブルを構築する。
 * v5 との差異は KB-17（未登録文字は NCR）と KB-18（不正バイトは `?`）のみ。
 * 背景・検証結果は docs/sjis差異一覧.md、方針は docs/移植方針_step3_ScalaJS.md R-01。
 */
@js.native
@JSGlobal("TextDecoder")
private class NativeTextDecoder(label: String) extends js.Object {
  def decode(input: Uint8Array): String = js.native
}

object SjisEncoding {

  /** 初回参照時に一度だけ構築する。decode 側は密な JS 配列（理由は docs/sjis差異一覧.md 5.2）。 */
  private val (encodeTable, rootTable): (Map[Int, Array[Int]], js.Array[js.Any]) = buildTables()

  /** v5 `sjis` コンストラクタの逐語的な再現（docs/sjis差異一覧.md 5.1）。 */
  private def buildTables(): (Map[Int, Array[Int]], js.Array[js.Any]) = {
    val td = new NativeTextDecoder("sjis")
    val encode = scala.collection.mutable.LinkedHashMap.empty[Int, Array[Int]]
    val root = js.Array[js.Any]()
    var z = 0
    while z < 256 do {
      root.push(js.undefined)
      z += 1
    }

    def record(bytes: Array[Int]): Unit = {
      val u8 = new Uint8Array(bytes.length)
      var k = 0
      while k < bytes.length do {
        u8(k) = bytes(k).toShort
        k += 1
      }
      val decoded = td.decode(u8)
      if decoded.length == 1 then {
        encode(decoded.charAt(0).toInt) = bytes
        if bytes.length == 1 then root(bytes(0)) = decoded
        else {
          val branch: js.Array[js.Any] =
            if js.isUndefined(root(bytes(0))) then {
              val b = js.Array[js.Any]()
              var w = 0
              while w < 256 do {
                b.push(js.undefined)
                w += 1
              }
              root(bytes(0)) = b
              b
            } else root(bytes(0)).asInstanceOf[js.Array[js.Any]]
          branch(bytes(1)) = decoded
        }
      }
    }

    var i = 0
    while i < 238 do {
      if i < 191 then record(Array(i + (if i < 128 then 0 else 0xa1 - 128)))
      else {
        val leadByte = (i - 191) + (if (i - 191) < 31 then 0x81 else 0xe0 - 31)
        var j = 0
        while j < 188 do {
          record(Array(leadByte, j + 0x40 + (if 62 < j then 1 else 0)))
          j += 1
        }
      }
      i += 1
    }

    (encode.toMap, root)
  }

  /** 文字列 → SJIS バイト列。未登録文字は NCR `&#codepoint;` にする（KB-17）。 */
  def encode(text: String): Uint8Array = {
    val out = js.Array[Int]()
    val n = text.length
    var i = 0
    while i < n do {
      val c1 = text.charAt(i)
      val cp: Int =
        if isHighSurrogate(c1) && i + 1 < n && isLowSurrogate(text.charAt(i + 1)) then {
          val combined = 0x10000 + ((c1.toInt - 0xd800) << 10) + (text.charAt(i + 1).toInt - 0xdc00)
          i += 2
          combined
        } else {
          i += 1
          c1.toInt
        }
      encodeTable.get(cp) match {
        case Some(bytes) => bytes.foreach(b => out.push(b))
        case None =>
          val ncr = s"&#$cp;"
          var k = 0
          while k < ncr.length do {
            out.push(ncr.charAt(k).toInt)
            k += 1
          }
      }
    }
    val result = new Uint8Array(out.length)
    var j = 0
    while j < out.length do {
      result(j) = out(j).toShort
      j += 1
    }
    result
  }

  /** SJIS バイト列 → 文字列。v5 が例外を投げる・捨てる箇所は `?` にする（KB-18）。 */
  def decode(bytes: Uint8Array): String = {
    val sb = new StringBuilder
    val n = bytes.length
    var i = 0
    while i < n do {
      val b = bytes(i) & 0xff
      val slot = rootTable(b)
      if js.typeOf(slot) == "string" then {
        sb.append(slot.asInstanceOf[String])
        i += 1
      } else if !js.isUndefined(slot) then
        // 枝（第2バイト用の配列）。v5と同様、ここまでの1バイトはまだ確定していない。
        if i + 1 < n then {
          val second = bytes(i + 1) & 0xff
          val leaf = slot.asInstanceOf[js.Array[js.Any]](second)
          if js.typeOf(leaf) == "string" then sb.append(leaf.asInstanceOf[String])
          else sb.append('?') // KB-18: 未知の第2バイト（両バイトを消費）
          i += 2
        } else {
          sb.append('?') // KB-18: 末尾の不完全なリードバイト（v5は無音で捨てる）
          i += 1
        } else {
        sb.append('?') // KB-18: 未知のバイト（KB-04のIBM拡張行 0xF0-0xFC を含む）
        i += 1
      }
    }
    sb.toString
  }

  private def isHighSurrogate(c: Char): Boolean = c >= 0xd800 && c <= 0xdbff
  private def isLowSurrogate(c: Char): Boolean = c >= 0xdc00 && c <= 0xdfff
}
