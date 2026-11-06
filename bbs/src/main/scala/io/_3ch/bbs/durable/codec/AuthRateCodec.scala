package io._3ch.bbs.durable.codec

import io._3ch.common.domain.account.*
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*

/** 認証要求の計数履歴を保存値へ変換する */
object AuthRateCodec {
  def decode(value: js.Any): AuthRateHistory = {
    if (value == null || js.isUndefined(value)) AuthRateHistory()
    else {
      if (!js.Array.isArray(value)) throw js.JavaScriptException(new js.Error("invalid-auth-rate-data"))
      val events = value.asInstanceOf[js.Array[js.Dynamic]].toVector.map { v =>
        if (v == null || js.typeOf(v.kind) != "string" || js.typeOf(v.t) != "number") {
          throw js.JavaScriptException(new js.Error("invalid-auth-rate-data"))
        }
        val event = AuthRateEvent(v.kind.asInstanceOf[String], v.t.asInstanceOf[Double])
        if (!AuthRateHistory.validKind(event.kind) || !AccountProfile.validTime(event.t)) throw js.JavaScriptException(new js.Error("invalid-auth-rate-data"))
        event
      }
      AuthRateHistory(events)
    }
  }
  def encode(history: AuthRateHistory): js.Array[js.Dynamic] = history.events.map(e => js.Dynamic.literal(kind = e.kind, t = e.t)).toJSArray
  def counts(value: Either[AccountRejection, AuthRateCounts]): js.Dynamic = value.fold(error => js.Dynamic.literal(code = error.code), result =>
    js.Dynamic.literal(code = "", hour = result.hour, day = result.day, distinctDevices24h = result.distinctDevices24h))
}
