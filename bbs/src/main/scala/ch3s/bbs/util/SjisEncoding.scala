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
   * 性能上の理由から、1文字ずつ `encoding-japanese` を呼ぶ実装は避け、次の3段構えで
   * 「大半の投稿は `encoding-japanese` への呼び出しが定数回で済む」ことを狙う（詳細は
   * `tests/golden/sjis-bench.mjs` のベンチマーク参照）:
   *   1. 重複符号マッピングに載っている文字（[[dupCodeOverrides]]、11件）が入力に1つも
   *      無ければ、そのまま2へ進む。あれば、そこを境に区間分割し、各区間は2で処理した上で
   *      該当バイト位置を強制的に挟み込む（v5と同じバイト位置を出力するため）。
   *   2. 区間全体を一括で `encoding-japanese` にエンコードさせ、その結果を一括でデコードし
   *      直して元の区間文字列に戻るか確認する（往復できない = 区間内に変換不能文字がある）。
   *      戻れば（実運用のほぼ全ての投稿がこれに該当する）その区間は1回の変換で完了する。
   *   3. 往復できなければ、区間をサロゲートペア境界を跨がないよう2分割して2を再帰する。
   *      1文字（またはサロゲートペア1組）まで縮んでも往復できない場合のみ、真に変換不能な
   *      文字と判定し、数値文字参照（NCR、`&#codepoint;`）へ置換してからエンコードする
   *      （KB-17の対応方針として台帳に記載した内容と一致させる）。
   *   これにより `encoding-japanese` の呼び出し回数は、区間分割数に比例した対数オーダーに
   *   抑えられる（変換不能文字が無ければ区間ごとに定数回）。1文字ずつ処理する版とバイト単位で
   *   完全に同じ結果になることは、本ファイルと同じアルゴリズムをミラーする
   *   `tests/golden/generate-sjis-compat.mjs` の全数照合、および [[SjisEncodingSuite]] の
   *   golden テストで確認済み。
   */
  def encode(text: String): Uint8Array =
    val out = js.Array[Int]()
    encodeSegment(text, out)
    val result = new Uint8Array(out.length)
    var j = 0
    while j < out.length do
      result(j) = out(j).toShort
      j += 1
    result

  /** 重複符号マッピングに載っている文字を境に区間分割し、各区間を [[encodeNoOverride]] に渡す。 */
  private def encodeSegment(text: String, out: js.Array[Int]): Unit =
    val n = text.length
    var start = 0
    var i = 0
    while i < n do
      val cp = text.charAt(i).toInt // 上書き対象は全てBMP内の単一UTF-16単位なのでサロゲート考慮不要
      encodeOverride.get(cp) match
        case Some((lead, second)) =>
          if i > start then encodeNoOverride(text.substring(start, i), out)
          out.push(lead)
          out.push(second)
          start = i + 1
        case None => ()
      i += 1
    if start < n then encodeNoOverride(text.substring(start, n), out)

  /**
   * 重複符号を含まないことが分かっている区間を、一括変換 → 往復検証 → (失敗時のみ)2分割再帰
   * で処理する。
   */
  private def encodeNoOverride(text: String, out: js.Array[Int]): Unit =
    if text.isEmpty then return
    val bytes = rawEncodeArray(text)
    if decodeRaw(bytes.map(_.toInt)) == text then bytes.foreach(b => out.push(b.toInt))
    else if isSingleChunk(text) then
      val cp = text.codePointAt(0)
      rawEncodeArray(s"&#$cp;").foreach(b => out.push(b.toInt))
    else
      val mid = splitPoint(text)
      encodeNoOverride(text.substring(0, mid), out)
      encodeNoOverride(text.substring(mid), out)

  /** 1文字、またはサロゲートペア1組ちょうどか。 */
  private def isSingleChunk(text: String): Boolean =
    text.length == 1 ||
      (text.length == 2 && isHighSurrogate(text.charAt(0)) && isLowSurrogate(text.charAt(1)))

  /** サロゲートペアを分断しない分割点を返す（`text.length >= 2` かつ単一チャンクでない前提）。 */
  private def splitPoint(text: String): Int =
    val len = text.length
    var mid = len / 2
    if mid > 0 && mid < len && isHighSurrogate(text.charAt(mid - 1)) && isLowSurrogate(text.charAt(mid)) then
      mid += 1
    if mid < 1 then mid = 1
    if mid > len - 1 then mid = len - 1
    mid

  /**
   * SJISバイト列 → 文字列。
   *
   * v5の状態遷移（先頭バイトが2バイト文字のリード範囲(0x81-0x9F, 0xE0-0xFC)かどうかで
   * 1バイト/2バイト単位を切り替える）を踏襲して自前で境界を判定するが、性能上の理由から
   * バイト単位で `encoding-japanese` を呼ぶことはしない。重複符号マッピングに載っている
   * バイト位置（[[dupCodeOverrides]]、11件）だけを境に区間分割し、それ以外の連続する区間は
   * まとめて1回で `encoding-japanese` に渡す（重複符号が入力に無ければ全体で1回のみ）。
   *   1. 重複符号マッピングに載っているバイト位置は、v5と同じ文字を返す
   *      （`encoding-japanese` 単体ではこの位置を正しく解釈できないため）。
   *   2. それ以外の区間は `encoding-japanese` でまとめてデコードする。
   * 末尾に不完全な（リードバイトだけの）データが残った場合や未対応バイトは、例外を投げず
   * 代替文字にフォールバックする（`encoding-japanese` の既定動作。KB-18の対応方針）。
   *
   * 区間単位のdecodeが1バイト/2バイト単位ごとのdecodeや一括decodeと同じ結果になることは
   * `tests/golden/generate-sjis-compat.mjs` の全数照合（隣接文字列チェックを含む）で検証済み
   * （区間の切れ目は元の状態遷移が単位を区切る位置と一致するため、区間をまたいで
   * `encoding-japanese` 側の解釈が変わることはない）。
   */
  def decode(bytes: Uint8Array): String =
    val sb = new StringBuilder
    val n = bytes.length
    var i = 0
    var runStart = 0
    while i < n do
      val b = bytes(i) & 0xff
      val isLead = (b >= 0x81 && b <= 0x9f) || (b >= 0xe0 && b <= 0xfc)
      if isLead && i + 1 < n then
        val second = bytes(i + 1) & 0xff
        decodeOverride.get((b, second)) match
          case Some(cp) =>
            if i > runStart then sb.append(decodeRawView(bytes.subarray(runStart, i)))
            sb.appendAll(Character.toChars(cp))
            runStart = i + 2
          case None => ()
        i += 2
      else
        i += 1
    if n > runStart then sb.append(decodeRawView(bytes.subarray(runStart, n)))
    sb.toString

  private def rawEncodeArray(text: String): js.Array[Double] =
    // `type`を省略すると（実測: encoding-japanese 2.2.0）配列ではなく「バイナリ文字列」が返るため、
    // 明示的に `type = "array"` を指定して `js.Array[Double]` を受け取る（他の型は "arraybuffer" 等）。
    val options = js.Dynamic.literal(to = "SJIS", from = "UNICODE", `type` = "array")
    convertFn(text, options).asInstanceOf[js.Array[Double]]

  private def decodeRaw(bytes: js.Array[Int]): String =
    val u8 = new Uint8Array(bytes.length)
    var i = 0
    while i < bytes.length do
      u8(i) = bytes(i).toShort
      i += 1
    decodeRawView(u8)

  private def decodeRawView(bytes: Uint8Array): String =
    val options = js.Dynamic.literal(to = "UNICODE", from = "SJIS", `type` = "string")
    convertFn(bytes, options).asInstanceOf[String]

  private def isHighSurrogate(c: Char): Boolean = c >= 0xd800 && c <= 0xdbff
  private def isLowSurrogate(c: Char): Boolean = c >= 0xdc00 && c <= 0xdfff
