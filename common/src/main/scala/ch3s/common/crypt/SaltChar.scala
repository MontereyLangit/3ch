package ch3s.common.crypt

/**
 * `src/common.js` の `translateSaltChar(c)` の Scala 3 移植。
 *
 * KB-19: 演算子優先順位のバグをそのまま再現する。JS のソースは
 * ```
 * return table[(code - (...base...) % table.length)];
 * ```
 * と書かれており、意図（`table[(code - base) % table.length]`）とは異なり、
 * `%` が `-` より結合が強いため `table[code - (base % table.length)]` として評価される。
 * さらにテーブル本体は「意図されたアルファベット」ではなく、リテラルの8文字
 * `"A-Ga-f./"`（ハイフンをそのまま含む）である。
 *
 * 結果として、変換対象とみなされる範囲（0x3A-0x40, 0x5B-0x60, 0x00-0x2D, 0x7B-0xFF）の
 * ほとんどは `table` の添字範囲(0-7)を超えて `undefined` を返す。0x00-0x07（制御コード）
 * だけが偶然範囲内に収まり、対応する文字を返す。
 *
 * 「正しい変換表」に修正してはいけない（CLAUDE.md §3.3 / 既知不具合台帳 KB-19）。
 * JS の `undefined` は `None` として表現する。
 */
object SaltChar {

  private val Table = "A-Ga-f./"

  /**
   * @param c 変換対象の1文字（JSの `c.charCodeAt(0)` に相当する `Char`）。
   * @return `Some(char)`: テーブル変換が成功した場合、または対象範囲外でそのまま返す場合。
   *         `None`: JSの `undefined` に相当（KB-19のバグにより添字が範囲外になった場合）。
   */
  def translateSaltChar(c: Char): Option[Char] = {
    val code = c.toInt
    val inTranslateRange =
      (code >= 0x3a && code <= 0x40) ||
        (code >= 0x5b && code <= 0x60) ||
        (code >= 0x00 && code <= 0x2d) ||
        (code >= 0x7b && code <= 0xff)

    if (inTranslateRange) {
      val base =
        if (code >= 0x7b) 0x7b
        else if (code >= 0x5b) 0x5b
        else if (code >= 0x3a) 0x3a
        else 0x00
      // KB-19: 意図した `(code - base) % table.length` ではなく `code - (base % table.length)`。
      val index = code - (base % Table.length)
      if (index >= 0 && index < Table.length) Some(Table.charAt(index)) else None
    } else {
      Some(c)
    }
  }
}
