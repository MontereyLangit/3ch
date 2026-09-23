package ch3s.bbs.util

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scala.scalajs.js.typedarray.Uint8Array

/**
 * Shift_JIS 変換（npm `encoding-japanese` のfacade）。
 *
 * docs/移植方針_step3_ScalaJS.md R-01（案A、2026-09-23確定）に基づく、v6s の唯一の公開 Shift_JIS
 * 変換経路。以前あった3実装（本ファイル / `common/src/main/scala/ch3s/common/sjis/Sjis.scala`
 * の自前テーブル移植 / `RuntimeSjis` の `TextDecoder` ランタイム構築）はここへ一本化し、他の2つは
 * 削除した。
 *
 * ## なぜ `encoding-japanese` だけでは足りないか
 *
 * CP932 には同じ文字が2箇所にエンコードされる「重複符号」がある。v5（`src/common.js` の `sjis`
 * クラス、`TextDecoder('sjis')` 由来）と `encoding-japanese` は、重複符号のうち採用する側が
 * 食い違う（v5は主にNEC特殊文字行 `0x87xx` 側、`encoding-japanese` はJIS X0208標準位置
 * `0x81xx` 側）。素のまま置き換えると:
 *   - encode: 新規投稿のバイト列が過去ログと食い違う。
 *   - decode: 既存datファイルに含まれる `0x87xx` 系バイト列を `encoding-japanese` は解釈できず
 *     `?` に化ける（過去ログ破壊）。
 * これを避けるため、[[dupCodeOverrides]] による双方向互換マッピングを encode / decode の
 * 両方向に被せる。
 *
 * ## マッピングの出自（手で9文字を決め打ちしていない）
 *
 * [[dupCodeOverrides]] は `tests/golden/generate-sjis-compat.mjs` が、v5の実測テーブル
 * （`tests/golden/sjis-table.json`、9,027件）と v5 の実際の encode 表（`src/common.js` の
 * `sjis` インスタンスが持つ `this.e`）の**全件**を `encoding-japanese` と突き合わせて機械的に
 * 抽出した結果である（`tests/golden/sjis-dup-code-table.json` が生成データ、
 * `docs/sjis差異一覧.md` が集計のまとめ）。結果は10件のNEC重複符号文字（docs/移植方針_step3_ScalaJS.md
 * R-01 に載る9文字 + `￢`(U+FFE2)）と、1件のU+FFFD（Unicode置換文字）用の副産物的な救済ペアの、
 * 計11件。後者はJIS規格上の「重複符号」ではないが、v5のテーブル構築（JIS X0208未定義の
 * 第2バイト位置がすべてUnicode置換文字に丸め込まれる）の副産物として `0xEF 0xFC` という
 * 特定の位置に決定的に collapse する（Node / 本番Workers(workerd) の両方で実測して確認済み、
 * `tests/golden/sjis-runtime-differences.json` に workerd 側との差異が無いことを確認済み）。
 * v5がこの文字を「正しく往復変換できていた」ことに変わりはないため、同じ上書き機構で救済する。
 *
 * ## 受け入れ基準に照らした残差異
 *
 * `tests/golden/generate-sjis-compat.mjs` は、本ファイルと同一のアルゴリズムをJSでミラーし、
 * v5の実表全件・BMP全域(U+0000-U+FFFF)のencode往復・絵文字等補助面サンプル・decode方向の
 * 2バイト全数スイープに対して全数照合し、「説明のつかない差異(UNEXPECTED)」が0件であることを
 * 確認済み（`tests/golden/sjis-compat-exceptions.json` に確定リストを出力）。残る差異は
 * 以下のみ:
 *   - KB-04: IBM拡張行(リードバイト0xF0-0xFC)。v5のテーブルに一切存在しない。
 *   - KB-17: v5のテーブルに存在しない文字(絵文字等)。v5はNULバイトになるが、本実装はNCRへ
 *     フォールバックする。
 *   - KB-18: v5が例外を投げる/無音破棄する不正バイト列。本実装は例外を投げずフォールバックする。
 *   - 制御コード0x1A/0x1C/0x7Fの見かけ上の差異: `tests/golden/sjis-table.json` はNodeの
 *     `TextDecoder('sjis')` で構築したものだが、本番Workers(workerd)の同APIはこの3バイトに
 *     ついてのみ異なる値を返す(`tests/golden/sjis-runtime-differences.json`)。workerdは
 *     `encoding-japanese` と同じ恒等変換であり、**本番のv5と本実装の間に実差異は無い**。
 *
 * 実装パターン（ScalablyTypedは使わず `js.Dynamic` 経由で呼ぶ手書きfacade）は参照実装
 * `C:/project/3ch/ch3s-migration/bbs/src/main/scala/ch3s/bbs/util/SjisEncoding.scala` を踏襲する。
 */
@js.native
@JSImport("encoding-japanese", JSImport.Namespace)
private object RawEncodingJapanese extends js.Object

object SjisEncoding:

  /**
   * v5 と `encoding-japanese` の双方向互換マッピング（tests/golden/sjis-dup-code-table.json と
   * 同一データ。生成過程は同ファイルおよび tests/golden/generate-sjis-compat.mjs 参照）。
   *
   * 各エントリ: (Unicodeコードポイント, v5が実際に使うバイト位置(lead, second))。
   * encode方向はこのバイト位置を強制する。decode方向はこのバイト位置に遭遇したら対応する文字を返す
   * （`encoding-japanese` 単体ではこの位置を正しく解釈できないため）。
   */
  private val dupCodeOverrides: List[(Int, (Int, Int))] = List(
    (0x222a, (0x87, 0x9c)), // ∪
    (0x2229, (0x87, 0x9b)), // ∩
    (0xffe2, (0xee, 0xf9)), // ￢
    (0x2220, (0x87, 0x97)), // ∠
    (0x22a5, (0x87, 0x96)), // ⊥
    (0x2261, (0x87, 0x91)), // ≡
    (0x2252, (0x87, 0x90)), // ≒
    (0x221a, (0x87, 0x95)), // √
    (0x2235, (0x87, 0x9a)), // ∵
    (0x222b, (0x87, 0x92)), // ∫
    (0xfffd, (0xef, 0xfc)) // U+FFFD（置換文字。CP932の重複符号ではなく、v5のテーブル構築上の
    // 副産物として決定的にこの位置へ収束することを実測で確認済み。詳細はクラスコメント参照）
  )

  private val encodeOverride: Map[Int, (Int, Int)] = dupCodeOverrides.toMap

  private val decodeOverride: Map[(Int, Int), Int] =
    dupCodeOverrides.map { case (cp, bytes) => bytes -> cp }.toMap

  // encoding-japaneseはCJS/ESM双方の配布形態でモジュール本体の生えている場所が変わりうる
  // （`mod` 直下か `mod.default` 配下）ため両対応する。
  private def moduleObj: js.Dynamic =
    val mod = RawEncodingJapanese.asInstanceOf[js.Dynamic]
    if !js.isUndefined(mod.default) && !js.isUndefined(mod.default.convert) then mod.default else mod

  private def convertFn: js.Dynamic = moduleObj.convert

  /**
   * 文字列 → SJISバイト列。
   *
   * サロゲートペアを1単位として扱いながら1文字（コードポイント）ずつ処理する:
   *   1. 重複符号マッピングに載っている文字は、v5と同じバイト位置を強制的に出力する。
   *   2. それ以外は `encoding-japanese` でエンコードし、その結果をデコードし直して元の文字に
   *      戻るか確認する（往復できない = 変換不能文字）。
   *   3. 往復できない文字は数値文字参照（NCR、`&#codepoint;`）へ置換してからエンコードする
   *      （KB-17の対応方針として台帳に記載した内容と一致させる）。
   */
  def encode(text: String): Uint8Array =
    val out = js.Array[Int]()
    var i = 0
    while i < text.length do
      val c = text.charAt(i)
      val isPair = isHighSurrogate(c) && i + 1 < text.length && isLowSurrogate(text.charAt(i + 1))
      val len = if isPair then 2 else 1
      val chunk = text.substring(i, i + len)
      encodeChunkInto(chunk, out)
      i += len
    val result = new Uint8Array(out.length)
    var j = 0
    while j < out.length do
      result(j) = out(j).toShort
      j += 1
    result

  /**
   * SJISバイト列 → 文字列。
   *
   * v5の状態遷移（先頭バイトが2バイト文字のリード範囲(0x81-0x9F, 0xE0-0xFC)かどうかで
   * 1バイト/2バイト単位を切り替える）を踏襲して自前で境界を判定し、各単位ごとに:
   *   1. 重複符号マッピングに載っているバイト位置は、v5と同じ文字を返す
   *      （`encoding-japanese` 単体ではこの位置を正しく解釈できないため）。
   *   2. それ以外は `encoding-japanese` でその単位だけをデコードする。
   * 末尾に不完全な（リードバイトだけの）データが残った場合や未対応バイトは、例外を投げず
   * 代替文字にフォールバックする（`encoding-japanese` の既定動作。KB-18の対応方針）。
   *
   * チャンク単位のdecodeが一括decodeと同じ結果になることは
   * `tests/golden/generate-sjis-compat.mjs` の全数照合（隣接文字列チェックを含む）で検証済み。
   */
  def decode(bytes: Uint8Array): String =
    val sb = new StringBuilder
    var i = 0
    val n = bytes.length
    while i < n do
      val b = bytes(i) & 0xff
      val isLead = (b >= 0x81 && b <= 0x9f) || (b >= 0xe0 && b <= 0xfc)
      if isLead && i + 1 < n then
        val second = bytes(i + 1) & 0xff
        decodeOverride.get((b, second)) match
          case Some(cp) => sb.appendAll(Character.toChars(cp))
          case None => sb.append(decodeRaw(js.Array(b, second)))
        i += 2
      else
        sb.append(decodeRaw(js.Array(b)))
        i += 1
    sb.toString

  private def encodeChunkInto(chunk: String, out: js.Array[Int]): Unit =
    if chunk.length == 1 then
      encodeOverride.get(chunk.codePointAt(0)) match
        case Some((lead, second)) =>
          out.push(lead)
          out.push(second)
          return
        case None => ()
    val bytes = rawEncodeArray(chunk)
    if decodeRaw(bytes.map(_.toInt)) == chunk then bytes.foreach(b => out.push(b.toInt))
    else
      val cp = chunk.codePointAt(0)
      rawEncodeArray(s"&#$cp;").foreach(b => out.push(b.toInt))

  private def rawEncodeArray(text: String): js.Array[Double] =
    // `type`を省略すると（実測: encoding-japanese 2.2.0）配列ではなく「バイナリ文字列」が返るため、
    // 明示的に `type = "array"` を指定して `js.Array[Double]` を受け取る（他の型は "arraybuffer" 等）。
    val options = js.Dynamic.literal(to = "SJIS", from = "UNICODE", `type` = "array")
    convertFn(text, options).asInstanceOf[js.Array[Double]]

  private def decodeRaw(bytes: js.Array[Int]): String =
    val options = js.Dynamic.literal(to = "UNICODE", from = "SJIS", `type` = "string")
    val u8 = new Uint8Array(bytes.length)
    var i = 0
    while i < bytes.length do
      u8(i) = bytes(i).toShort
      i += 1
    convertFn(u8, options).asInstanceOf[String]

  private def isHighSurrogate(c: Char): Boolean = c >= 0xd800 && c <= 0xdbff
  private def isLowSurrogate(c: Char): Boolean = c >= 0xdc00 && c <= 0xdfff
