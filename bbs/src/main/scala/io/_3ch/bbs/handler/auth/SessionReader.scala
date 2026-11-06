package io._3ch.bbs.handler.auth

import io._3ch.bbs.external.cloudflare.WorkerEnv
import io._3ch.bbs.external.web.Request
import io._3ch.bbs.external.hono.HonoContextFacade
import io._3ch.common.domain.account.SessionView
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

/** SSR で使う検証済みのセッション。アカウントの最新状態は含まない */
final case class SessionSnapshot(aid: String, uid: String, lv: Int) {
  /** クライアントへ渡す表示情報 */
  def view: SessionView = SessionView(uid, lv)
}

/** アカウント DO を呼ばず、Cookie の署名と期限だけで表示を出し分ける */
object SessionReader {
  /** 要求の Cookie から表示情報を読む */
  def fromRequest(request: Request, env: WorkerEnv): Future[Option[SessionSnapshot]] =
    read(request.headers.get("Cookie"), env.AUTH_SECRET.getOrElse(""), js.Date.now() / 1000)

  /** Hono の要求から表示情報を読む */
  def fromRequest(context: HonoContextFacade): Future[Option[SessionSnapshot]] =
    read(context.req.header("Cookie").getOrElse(""), context.env.AUTH_SECRET.getOrElse(""), js.Date.now() / 1000)

  /** 時刻と鍵を指定して表示情報を読む */
  def read(cookieHeader: String, secret: String, nowSeconds: Double): Future[Option[SessionSnapshot]] =
    SessionCookies.verify(cookieHeader, secret, nowSeconds).map(_.map(claims => SessionSnapshot(claims.aid, claims.uid, claims.lv)))
}
