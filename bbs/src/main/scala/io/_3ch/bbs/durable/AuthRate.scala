package io._3ch.bbs.durable

import io._3ch.bbs.external.cloudflare.{DurableObject, DurableObjectState}
import io._3ch.bbs.durable.codec.AuthRateCodec
import io._3ch.bbs.durable.support.DurableLock
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.scalajs.js.annotation.JSExportTopLevel

/** 接続元ごとの認証要求を直列に計数する */
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
}
