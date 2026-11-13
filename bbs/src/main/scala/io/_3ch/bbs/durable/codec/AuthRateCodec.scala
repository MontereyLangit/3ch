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
  def decodeReview(value: js.Any): AbuseReview = {
    def invalid(): Nothing = throw js.JavaScriptException(new js.Error("invalid-abuse-review-data"))
    if (value == null || js.isUndefined(value)) AbuseReview()
    else if (!js.Array.isArray(value)) invalid()
    else AbuseReview(value.asInstanceOf[js.Array[js.Dynamic]].toVector.map { v =>
      def number(name: String): Double = if (js.typeOf(v.selectDynamic(name)) == "number") v.selectDynamic(name).asInstanceOf[Double] else invalid()
      val reason = if (js.typeOf(v.reason) == "string") AbuseReason.parse(v.reason.asInstanceOf[String]).getOrElse(invalid()) else invalid()
      val hash = if (js.typeOf(v.keyIpHash) == "string") v.keyIpHash.asInstanceOf[String] else invalid()
      val flag = AbuseFlag(hash, reason, number("firstSeen"), number("lastSeen"), number("count").toInt, number("hour").toInt, number("day").toInt, number("devices").toInt)
      if (!flag.valid) invalid()
      flag
    })
  }
  def encodeReview(review: AbuseReview): js.Array[js.Dynamic] = review.flags.map(f => js.Dynamic.literal(keyIpHash = f.keyIpHash, reason = f.reason.code,
    firstSeen = f.firstSeen, lastSeen = f.lastSeen, count = f.count, hour = f.hour, day = f.day, devices = f.devices)).toJSArray

  def counts(value: Either[AccountRejection, AuthRateCounts]): js.Dynamic = value.fold(error => js.Dynamic.literal(code = error.code), result =>
    js.Dynamic.literal(code = "", hour = result.hour, day = result.day, distinctDevices24h = result.distinctDevices24h))
}
