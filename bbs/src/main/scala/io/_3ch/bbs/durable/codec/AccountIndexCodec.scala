package io._3ch.bbs.durable.codec

import io._3ch.common.domain.account.*
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*

/** 索引の保存形式と予約・確定の結果を変換する */
object AccountIndexCodec {
  def decode(value: js.Any): Option[AccountIndexEntry] = {
    if (value == null || js.isUndefined(value)) None
    else {
      val v = value.asInstanceOf[js.Dynamic]
      if (js.typeOf(v.aid) != "string" || js.typeOf(v.createdAt) != "number" || js.typeOf(v.state) != "string" ||
        (!js.isUndefined(v.tokenId) && js.typeOf(v.tokenId) != "string")) {
        throw js.JavaScriptException(new js.Error("invalid-account-index-data"))
      }
      val entry = AccountIndexEntry(v.aid.asInstanceOf[String], v.createdAt.asInstanceOf[Double],
        v.state.asInstanceOf[String] == "confirmed", v.tokenId.asInstanceOf[js.UndefOr[String]].toOption)
      if (!entry.aid.matches("[a-z0-9]{16}") || !AccountProfile.validTime(entry.createdAt) ||
        !Set("pending", "confirmed").contains(v.state.asInstanceOf[String]) || entry.tokenId.exists(id => !DedicatedBrowserTokens.validId(id))) {
        throw js.JavaScriptException(new js.Error("invalid-account-index-data"))
      }
      Some(entry)
    }
  }
  def encode(entry: AccountIndexEntry): js.Dynamic = js.Dynamic.literal(aid = entry.aid, state = if (entry.confirmed) "confirmed" else "pending",
    createdAt = entry.createdAt, tokenId = entry.tokenId.orUndefined)
  def lookup(entry: Option[AccountIndexEntry]): js.Dynamic = entry.filter(_.confirmed).fold(js.Dynamic.literal(code = "auth:index:missing"))(value =>
    js.Dynamic.literal(code = "", aid = value.aid, tokenId = value.tokenId.orUndefined))
}
