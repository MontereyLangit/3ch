package ch3s.bbs.hash

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
@JSImport("unix-crypt-td-js", JSImport.Default)
private object UnixCryptTD extends js.Object {
  def apply(pw: String, salt: String): String = js.native
  def apply(pw: String, salt: String, returnBytes: Boolean): js.Array[js.UndefOr[Int]] = js.native
}

/**
 * Unix crypt(3) DES（トリップ用）。npm の `unix-crypt-td-js` に委譲する（docs/v6s実装メモ.md §3.3）。
 * KB-26: 2文字未満の salt を渡したときの挙動（NUL 混入）も v5 と同じ。
 */
object UnixCrypt {
  def crypt(pw: String, salt: String): String = UnixCryptTD(pw, salt)

  /** `crypt(pw, salt, true)` 相当。salt 不足で `undefined` になった要素は `None`（KB-26）。 */
  def cryptBytes(pw: String, salt: String): List[Option[Int]] =
    UnixCryptTD(pw, salt, true).toList.map(_.toOption)
}
