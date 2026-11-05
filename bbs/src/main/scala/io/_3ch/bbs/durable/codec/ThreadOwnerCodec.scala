package io._3ch.bbs.durable.codec

import io._3ch.bbs.domain.posting.ThreadOwner
import scala.scalajs.js

/** Thread DO の `owner` キーの保存形式と RPC 引数の変換（docs/トリップ・ID廃止とスレ主表示.md §2.4）。欠けた項目は空文字として読む。 */
object ThreadOwnerCodec {
  def decode(value: js.Dynamic): ThreadOwner = ThreadOwner(field(value, "ip"), field(value, "shikibetsu"), field(value, "account"))

  def encode(owner: ThreadOwner): js.Dynamic = js.Dynamic.literal(ip = owner.ip, shikibetsu = owner.shikibetsu, account = owner.account)

  private def field(value: js.Dynamic, name: String): String = {
    val v = value.selectDynamic(name)
    if (v == null || js.isUndefined(v)) "" else v.asInstanceOf[String]
  }
}
