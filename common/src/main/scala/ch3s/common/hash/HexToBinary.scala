package ch3s.common.hash

/**
 * `src/common.js` の `hexToBinary(hex)` の Scala 3 移植。
 * 16進文字列 → 1バイト1文字のバイナリ文字列（各 `Char` の下位8bitがそのバイト値）。
 *
 * JS 実装:
 * ```
 * Array.from({ length: hex.length / 2 }, (_, i) =>
 *   String.fromCharCode(parseInt(hex.substr(i * 2, 2), 16))
 * ).join('');
 * ```
 * `hex.length / 2` は `Array.from({length: ...})` に渡る時点で JS 側が非整数の `length` を
 * 自動的に切り捨てる（`Array.from` の `length` プロパティは `ToLength` で非負整数化される）。
 * これにより奇数長の入力は末尾の半端な1文字が黒子のまま切り捨てられる（characterization
 * テストで固定済みの挙動。バグとまでは言えないが意図的な動作ではない）。
 */
object HexToBinary {

  def hexToBinary(hex: String): String = {
    val n = hex.length / 2 // Scalaの整数除算も同じく切り捨て（floor、常に非負のためJSのToLengthと一致）
    val sb = new StringBuilder(n)
    var i = 0
    while (i < n) {
      val byte = Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16)
      sb.append(byte.toChar)
      i += 1
    }
    sb.toString
  }
}
