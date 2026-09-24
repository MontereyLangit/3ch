package ch3s.bbs.util

import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal
import scala.scalajs.js.typedarray.Uint8Array

/**
 * Shift_JIS 変換（v6s の唯一の公開 Shift_JIS 変換経路）。
 *
 * docs/移植方針_step3_ScalaJS.md R-01（**2026-09-23、`encoding-japanese` 案Aを破棄し
 * 「v5と同じ構築手順」へ戻す最終決定**）に基づく実装。以前あった `encoding-japanese`
 * ベースの実装（NEC重複符号の双方向互換マッピング11件による上書き）はここで廃止した。
 *
 * ## なぜ `encoding-japanese` をやめたか
 *
 * - 「v5のテーブルは不完全」（KB-04）は仕様調査の誤りが大半だった。v5のテーブルはNEC特殊文字行
 *   (`0x87xx`)やNEC選定IBM拡張(`0xED40`→`纊`)を**正しく**変換できる。実際に無いのはIBM拡張行
 *   `0xF0`-`0xFC` と絵文字のみ。
 * - 「ランタイム構築はNodeとworkerdで表が違うから危険」（R-06）は実測で否定された。Node/workerd間
 *   で443箇所（制御文字3件・未割当位置440件）差異はあるが、v5-on-workerdとv6-on-workerdを直接
 *   突き合わせた結果、**説明のつかない差異は0件**（`tests/golden/sjis-runtime-differences.json`、
 *   `tests/golden/http-differential.test.mjs` PART 1参照）。
 * - `encoding-japanese`は独自の固定テーブルを持ち、v5とは重複符号の採用側が食い違う
 *   （`≒`をv5は`0x8790`、`encoding-japanese`は`0x81E0`でエンコードし、`0x8790`を単体では
 *   デコードできず`?`に化ける）。これを補うために11件の手動互換マッピングが必要だった。
 * - 素の`encoding-japanese`呼び出しはテーブル参照より2.8〜5.4倍遅い（最適化後）。
 *
 * ## 採用した方式: v5と同じ構築手順をそのまま再現する
 *
 * `src/common.js` の `sjis` クラスのコンストラクタを**逐語的に**再現する（[[buildTables]]）。
 * `new TextDecoder('sjis')` を同じ範囲・同じ順序・同じ「単一文字判定」（decode結果の
 * `length == 1`）で走査してテーブルを構築するため、実行環境が持つ実際のShift_JIS実装に
 * 由来する点も含めてv5と完全に同一になる。v5は呼び出しごとに `new sjis()` していたが、
 * 本実装はモジュール初期化時（`object` の初回参照時）に一度だけ構築し、以後は再利用する
 * （v5より高速になる理由。`tests/golden/sjis-bench.mjs` 参照）。
 *
 * 重複符号の互換マッピングは**不要になった**。v5自身が使うテーブルを直接使っているため、
 * v5が `0x8790` を選ぶ文字は本実装も必然的に `0x8790` を選ぶ（別ライブラリの語彙を借りて
 * 後から補正する必要がそもそも無い）。
 *
 * ## 唯一の意図的な差異（KB-17 / KB-18。それ以外は全てv5と一致する）
 *
 * - **KB-17（encode）**: テーブルに存在しない文字（IBM拡張行・絵文字等）をv5はNULバイト
 *   `0x00`にする（`Uint8Array.from([undefined])`の副作用）。本実装は数値文字参照
 *   （NCR、`&#codepoint;`）にフォールバックする。
 * - **KB-18（decode）**: v5は未知の第2バイトに`TypeError`を送出し、末尾の不完全な
 *   リードバイトは無音で捨てる。本実装は例外を投げず、置換文字`?`にフォールバックする
 *   （廃止した`encoding-japanese`ベース実装が実際に使っていたフォールバック文字と同一。
 *   ダウンストリームの出力を変えないため、この文字を継承した）。
 *
 * この2件のみが許容される差異であり、[[SjisEncodingSuite]] が
 * `tests/golden/sjis-table.json`（9,027件）・BMP全域encode往復・decode 1/2バイト全数スイープに
 * 対して全数照合し、未列挙の差異(UNEXPECTED)が0件であることを確認する。
 */
@js.native
@JSGlobal("TextDecoder")
private class NativeTextDecoder(label: String) extends js.Object {
  def decode(input: Uint8Array): String = js.native
}

object SjisEncoding {

  /**
   * v5と同一のテーブルを一度だけ構築する（[[buildTables]]）。v5は呼び出しごとに `new sjis()`
   * していたが、本実装は `object` の初回参照時に一度だけ構築し以後再利用する。
   *
   * decode側（[[rootTable]]）はScalaの`Map`ではなく生のJS配列にした。バイト値(0-255)を
   * 添字とする密な配列にすることで、v5自身の `this.d[byte]` というオブジェクトプロパティ
   * アクセス1回と同等の速さで、葉(文字)か枝(第2バイト用の配列)かを1回のルックアップで
   * 判別できる（`Map[Int,Char]`と`Map[Int,Map[Int,Char]]`を分けて持つと、2バイト文字1つに
   * つき「単バイト側で失敗→2バイト側で成功」の2回引きが必要になり、実測で decode が
   * v5より遅くなった。配列化してこの無駄な1回引きを消した）。
   */
  private val (encodeTable, rootTable): (Map[Int, Array[Int]], js.Array[js.Any]) = buildTables()

  /**
   * `src/common.js` の `sjis` クラスのコンストラクタを逐語的に再現する。
   *
   * ```js
   * ((td = new TextDecoder('sjis'), f = w =>
   *   (_ => _.length == 1 &&
   *     (this.e[_] = w).reduce((a, x, i, { length: l }) =>
   *       (i == l - 1 ? a[x] = _ : a[x] ||= {}), this.d)
   *   )(td.decode(new Uint8Array(w)))
   * ) => [...Array(238)].forEach((_, i) =>
   *   i < 191
   *     ? f([i + (i < 128 ? 0 : 0xa1 - 128)])
   *     : [...Array(188)].forEach((_, j) =>
   *       f([i - 191 + (i - 191 < 31 ? 0x81 : 0xe0 - 31),
   *         j + 0x40 + (62 < j ? 1 : 0)]))
   * ))();
   * ```
   *
   * `i < 191`: 単バイト側。`byte = i + (i<128 ? 0 : 0xa1-128)` で `0x00-0x7F` と `0xA1-0xDF`
   * （半角カナ）を走査する。それ以外（`i` は 47 通り）: 2バイト側。リードバイトは
   * `0x81-0x9F`（31通り）・`0xE0-0xEF`（16通り、= `0xF0-0xFC` のIBM拡張行はそもそも走査対象外
   * = KB-04）、第2バイトは `j` 188通りで `0x40-0x7E` ・ `0x80-0xFC`（`0x7F` を除く）。
   * 各バイト列を実際に`TextDecoder('sjis').decode`し、結果が長さ1（サロゲートペアや複数文字に
   * ならない、単一のUTF-16コード単位）の場合のみテーブルへ登録する（v5の`_.length==1`判定）。
   */
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

  /**
   * 文字列 → SJISバイト列。
   *
   * v5の`encode(w) { return [...w].flatMap(x => this.e[x]); }` と同じ単位（文字列イテレータ、
   * サロゲートペアは1要素）で走査し、テーブルに存在すればv5と同一のバイト列を出力する。
   * 存在しない場合（KB-17）は、v5のNULバイトの代わりにNCR（`&#codepoint;`）へフォールバックする。
   */
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

  /**
   * SJISバイト列 → 文字列。
   *
   * v5の`decode`（`this.d`のトライを1バイトずつ辿り、葉に達したら`this.a`へ追記してルートへ戻る）
   * と同じ状態遷移をたどるが、v5が`TypeError`を送出する箇所（未知のリードバイト、リードバイトに
   * 対する未知の第2バイト）と、v5が無音で捨てる箇所（末尾に不完全なリードバイトが残る）を、
   * 例外を投げずに`?`へフォールバックする（KB-18。廃止した`encoding-japanese`ベース実装の
   * フォールバック文字を継承し、ダウンストリームの出力を変えない）。
   */
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
