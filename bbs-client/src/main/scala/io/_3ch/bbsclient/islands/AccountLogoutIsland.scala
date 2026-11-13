package io._3ch.bbsclient.islands

import japgolly.scalajs.react.*
import japgolly.scalajs.react.vdom.html_<^.*
import org.scalajs.dom
import scala.scalajs.js
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

/** アカウント設定画面のログアウト。かんたん登録では戻せないことを先に伝える */
object AccountLogoutIsland {
  private val button = "cursor-pointer border border-[#d6dce3] bg-white px-3 py-1 disabled:opacity-50"
  final val Component = ScalaFnComponent[js.Object] { raw =>
    for {
      busy <- useState(false)
      message <- useState("")
    } yield {
      val logout = busy.setState(true) >> message.setState("") >> Callback {
        AuthClient.logout().foreach { ok =>
          if ok then dom.window.location.assign("/")
          else {
            busy.setState(false).runNow()
            message.setState("ログアウトできませんでした。もう一度お試しください").runNow()
          }
        }
      }
      <.section(^.id := "logout", ^.className := "my-4 border border-[#d6dce3] bg-white p-4",
        <.h2(^.className := "mb-2 font-bold", "■ ログアウト"),
        <.button(^.`type` := "button", ^.className := button, ^.disabled := busy.value, ^.onClick --> logout, "ログアウト（すべての端末）"),
        if AuthClient.flag(raw, "simple") then <.p(^.className := "mt-2 text-xs text-[#697785]", "かんたん登録のアカウントは、ログアウトするとこの端末から戻せません。")
        else EmptyVdom,
        if message.value.nonEmpty then <.p(^.role := "alert", ^.className := "mt-2 text-[#f00]", message.value) else EmptyVdom)
    }
  }
}
