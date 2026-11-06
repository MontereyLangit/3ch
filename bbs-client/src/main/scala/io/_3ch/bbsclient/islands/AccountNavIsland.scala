package io._3ch.bbsclient.islands

import japgolly.scalajs.react.*
import japgolly.scalajs.react.vdom.html_<^.*
import org.scalajs.dom
import scala.scalajs.js
import scala.scalajs.js.URIUtils
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

/** ヘッダーの認証状態とアカウントメニュー */
object AccountNavIsland {
  final val Component = ScalaFnComponent[js.Object] { raw =>
    for {
      session <- AuthClient.useSession(AuthClient.initial(raw))
    } yield {
      val authUrl = "/auth/?return=" + URIUtils.encodeURIComponent(AuthClient.text(raw, "returnPath", "/"))
      session match {
        case None => <.span(<.a(^.href := authUrl, "認証・登録"), <.span(" | "), <.a(^.href := s"$authUrl&mode=login", "ログイン"))
        case Some(s) => <.details(^.className := "relative",
          <.summary(^.className := "cursor-pointer", <.b(s"@${s.uid}"), <.span(^.className := "ml-2 border border-[#d6dce3] px-1 text-xs", if s.lv >= 10 then "Lv*" else s"Lv${s.lv}")),
          <.div(^.className := "absolute right-0 z-20 min-w-56 border border-[#d6dce3] bg-white p-3 shadow-sm [&_a]:block [&_a]:py-1",
            <.a(^.href := "/auth/account", "アカウント設定"), <.a(^.href := "/auth/account#key", "専ブラ用認証キー"),
            <.a(^.href := "/auth/account#cap", "キャップ"), <.a(^.href := "/auth/account?upgrade=1", "登録方法の切り替え"),
            <.form(^.action := "/auth/logout", ^.method := "post", ^.onSubmit ==> ((e: ReactEventFromHtml) => e.preventDefaultCB >> Callback {
              dom.fetch("/auth/logout", new dom.RequestInit { method = dom.HttpMethod.POST; credentials = dom.RequestCredentials.`same-origin` }).toFuture.foreach { r => if r.ok then AuthClient.changed(None) }
            }), <.button(^.`type` := "submit", ^.className := "cursor-pointer py-1", "ログアウト"))))
      }
    }
  }
}
