package io._3ch.bbs.durable

import io._3ch.bbs.external.cloudflare.{DurableObject, DurableObjectState}
import io._3ch.bbs.durable.codec.AuthRateCodec
import io._3ch.bbs.durable.support.DurableLock
import io._3ch.common.domain.account.{AbuseReason, AccountRejection, AuthRateCounts}
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.scalajs.js.annotation.JSExportTopLevel

/** 接続元ごとの認証要求を直列に計数する。名前が review のものは乱造検知の「要確認」一覧を持つ */
@JSExportTopLevel("AuthRate", "server")
class AuthRate(val state: DurableObjectState, env: js.Any) extends DurableObject(state, env) {
  /** 一件を追加した後の一時間・一日窓と相異なる端末数を返す */
  def hit(kind: String, now: Double): js.Promise[js.Dynamic] = DurableLock.serialized(state) {
    state.storage.get[js.Any]("events").toFuture.flatMap { value =>
      AuthRateCodec.decode(value.asInstanceOf[js.Any]).hit(kind, now) match {
        case Left(error) => Future.successful(AuthRateCodec.counts(Left(error)))
        case Right((history, counts)) => state.storage.put("events", AuthRateCodec.encode(history)).toFuture.map(_ => AuthRateCodec.counts(Right(counts)))
      }
    }
  }.toJSPromise

  /** 「要確認」に一件を加える。同じ接続元と理由は 24 時間以内ならまとめる */
  def flag(keyIpHash: String, reason: String, hour: Int, day: Int, devices: Int, now: Double): js.Promise[String] = DurableLock.serialized(state) {
    AbuseReason.parse(reason).fold(Future.successful(AccountRejection.Invalid.code)) { value =>
      state.storage.get[js.Any]("review").toFuture.flatMap { stored =>
        AuthRateCodec.decodeReview(stored.asInstanceOf[js.Any]).flag(keyIpHash, value, AuthRateCounts(hour, day, devices), now) match {
          case Left(error) => Future.successful(error.code)
          case Right(next) => state.storage.put("review", AuthRateCodec.encodeReview(next)).toFuture.map(_ => "")
        }
      }
    }
  }.toJSPromise

  /** 「要確認」の一覧を新しい順に返す */
  def flagged(): js.Promise[js.Array[js.Dynamic]] =
    state.storage.get[js.Any]("review").toFuture.map(v => AuthRateCodec.encodeReview(AuthRateCodec.decodeReview(v.asInstanceOf[js.Any]))).toJSPromise
}
