package ch3s.bbs.durable.support

import ch3s.bbs.external.cloudflare.DurableObjectState
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*

/** `blockConcurrencyWhile` で処理を直列化する。失敗はゲートの内側で捕捉し、ゲートの外で同じメッセージの Error として投げ直す。 */
object DurableLock {
  // Catch inside the concurrency gate: rejecting the gate would reset the DO.
  def serialized[A](state: DurableObjectState)(body: => Future[A]): Future[A] =
    state.blockConcurrencyWhile[js.Dynamic](() =>
      Future.unit.flatMap(_ => body).map(value => js.Dynamic.literal(value = value.asInstanceOf[js.Any])).recover {
        case error: js.JavaScriptException => js.Dynamic.literal(failed = true, error = error.exception.asInstanceOf[js.Dynamic].message)
        case error => js.Dynamic.literal(failed = true, error = error.getMessage)
      }.toJSPromise
    ).toFuture.map { result =>
      if result.failed.asInstanceOf[js.UndefOr[Boolean]].contains(true) then {
        throw js.JavaScriptException(new js.Error(js.Dynamic.global.String(result.error).asInstanceOf[String]))
      }
      result.value.asInstanceOf[A]
    }
}
