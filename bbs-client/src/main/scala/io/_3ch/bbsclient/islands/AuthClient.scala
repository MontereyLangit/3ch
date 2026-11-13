package io._3ch.bbsclient.islands

import io._3ch.common.domain.account.SessionView
import japgolly.scalajs.react.*
import org.scalajs.dom
import scala.concurrent.{Future, Promise}
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

/** 認証の表示情報とクライアント間通知 */
private[islands] object AuthClient {
  def session(raw: js.Any): Option[SessionView] = {
    if raw == null || js.typeOf(raw) != "object" then None
    else {
      val o = raw.asInstanceOf[js.Dynamic]
      val uid = o.selectDynamic("uid")
      val lv = o.selectDynamic("lv")
      Option.when(js.typeOf(uid) == "string" && js.typeOf(lv) == "number")(SessionView(uid.asInstanceOf[String], lv.asInstanceOf[Double].toInt))
    }
  }
  def initial(raw: js.Object): Option[SessionView] = session(raw.asInstanceOf[js.Dynamic].selectDynamic("session"))
  def text(raw: js.Object, name: String, default: String = ""): String =
    raw.asInstanceOf[js.Dynamic].selectDynamic(name).asInstanceOf[js.UndefOr[String]].getOrElse(default)
  def flag(raw: js.Object, name: String): Boolean =
    raw.asInstanceOf[js.Dynamic].selectDynamic(name).asInstanceOf[js.UndefOr[Boolean]].getOrElse(false)
  def readSession(): Future[Option[SessionView]] = {
    dom.fetch("/auth/session", new dom.RequestInit { credentials = dom.RequestCredentials.`same-origin`; cache = dom.RequestCache.`no-store` }).toFuture
      .flatMap(r => if r.ok then r.json().toFuture.map(session) else Future.successful(None)).recover { case _ => None }
  }
  def event(name: String, detail: js.Object): Unit = {
    val e = js.Dynamic.newInstance(js.Dynamic.global.CustomEvent)(name, js.Dynamic.literal(detail = detail)).asInstanceOf[dom.Event]
    dom.window.dispatchEvent(e)
  }
  /** フォームの値を認証 API へ送り、成否と JSON の本文を返す。通信の失敗は Future の失敗 */
  def post(path: String, fields: Seq[(String, String)]): Future[(Boolean, js.Object)] = {
    val params = new dom.URLSearchParams()
    fields.foreach { case (name, value) => params.set(name, value) }
    dom.fetch(path, new dom.RequestInit { method = dom.HttpMethod.POST; credentials = dom.RequestCredentials.`same-origin`; body = params }).toFuture
      .flatMap(r => r.json().toFuture.map(json => (r.ok, json.asInstanceOf[js.Object])))
  }
  /** 失敗の応答の文言 */
  def error(json: js.Object, fallback: String = "操作できませんでした"): String =
    if json == null then fallback else text(json, "error", fallback)

  /** ログアウトを送る。成功したら表示をログアウト状態にする */
  def logout(): Future[Boolean] = {
    dom.fetch("/auth/logout", new dom.RequestInit {
      method = dom.HttpMethod.POST; credentials = dom.RequestCredentials.`same-origin`; body = new dom.URLSearchParams()
    }).toFuture.map { r =>
      if r.ok then changed(None)
      r.ok
    }.recover { case _ => false }
  }
  def changed(value: Option[SessionView]): Unit = event("ch3s:auth-changed", value.fold[js.Object](js.Dynamic.literal())(s => js.Dynamic.literal(uid = s.uid, lv = s.lv)))
  def requestLogin(): Future[Boolean] = {
    val promise = Promise[Boolean]()
    val resolve: js.Function1[Boolean, Unit] = ok => { promise.trySuccess(ok); () }
    val detail = js.Dynamic.literal(resolve = resolve, handled = false)
    event("ch3s:auth-open", detail)
    if !detail.handled.asInstanceOf[Boolean] then promise.trySuccess(false)
    promise.future
  }
  def useSession(initial: Option[SessionView]): HookResult[Option[SessionView]] = {
    for {
      state <- useState(initial)
      _ <- useEffectOnMount(CallbackTo {
        val listener: js.Function1[dom.Event, Unit] = e => state.setState(session(e.asInstanceOf[js.Dynamic].detail.asInstanceOf[js.Any])).runNow()
        dom.window.addEventListener("ch3s:auth-changed", listener)
        Callback(dom.window.removeEventListener("ch3s:auth-changed", listener))
      })
    } yield state.value
  }
}
