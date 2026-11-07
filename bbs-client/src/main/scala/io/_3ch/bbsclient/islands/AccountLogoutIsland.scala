package io._3ch.bbsclient.islands

import japgolly.scalajs.react.*
import japgolly.scalajs.react.vdom.html_<^.*
import org.scalajs.dom
import scala.scalajs.js
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

/** アカウント設定画面のログアウトと退会。かんたん登録では戻せないことを先に伝える */
object AccountLogoutIsland {
  private val button = "cursor-pointer border border-[#d6dce3] bg-white px-3 py-1 disabled:opacity-50"
  private val input = "mt-1 block w-full max-w-sm border border-[#d6dce3] p-1"

  /** Google の再認証で退会する。フォームの遷移で送る */
  private def startGoogle(): Unit = {
    val form = dom.document.createElement("form").asInstanceOf[dom.html.Form]
    form.method = "post"
    form.action = "/auth/google/start"
    Seq("purpose" -> "delete", "return" -> "/auth/account/", "device" -> js.JSON.stringify(BrowserFingerprintCollector.collect())).foreach { case (name, value) =>
      val field = dom.document.createElement("input").asInstanceOf[dom.html.Input]
      field.`type` = "hidden"
      field.name = name
      field.value = value
      form.appendChild(field)
    }
    dom.document.body.appendChild(form)
    form.submit()
    form.remove()
  }

  final val Component = ScalaFnComponent[js.Object] { raw =>
    for {
      busy <- useState(false)
      message <- useState("")
      deleting <- useState(false)
      password <- useState("")
      agreed <- useState(false)
    } yield {
      val kind = AuthClient.text(raw, "kind", "client")
      val logout = busy.setState(true) >> message.setState("") >> Callback {
        AuthClient.logout().foreach { ok =>
          if ok then dom.window.location.assign("/")
          else {
            busy.setState(false).runNow()
            message.setState("ログアウトできませんでした。もう一度お試しください").runNow()
          }
        }
      }
      val withdraw = busy.setState(true) >> message.setState("") >> Callback {
        val fields = Seq("action" -> "delete") ++ (if kind == "password" then Seq("password" -> password.value) else Seq("confirm" -> "1"))
        AuthClient.post("/auth/account", fields).map { case (ok, json) =>
          if ok then {
            AuthClient.changed(None)
            dom.window.location.assign("/")
          } else {
            busy.setState(false).runNow()
            message.setState(AuthClient.error(json, "退会できませんでした")).runNow()
          }
        }.recover { case _ => busy.setState(false).runNow(); message.setState("通信できませんでした").runNow() }
      }
      <.section(^.id := "logout", ^.className := "my-4 border border-[#d6dce3] bg-white p-4",
        <.h2(^.className := "mb-2 font-bold", "■ ログアウト・退会"),
        <.button(^.`type` := "button", ^.className := button, ^.disabled := busy.value, ^.onClick --> logout, "ログアウト（すべての端末）"),
        <.button(^.`type` := "button", ^.className := s"$button ml-2 text-[#c00]", ^.disabled := busy.value, ^.onClick --> deleting.modState(!_), "退会する"),
        if AuthClient.flag(raw, "simple") then <.p(^.className := "mt-2 text-xs text-[#697785]", "かんたん登録のアカウントは、ログアウトするとこの端末から戻せません。")
        else EmptyVdom,
        <.p(^.className := "mt-2 text-xs text-[#697785]", "退会しても書き込みは消えません。ユーザーIDはほかの人に使われません。"),
        if !deleting.value then EmptyVdom
        else <.div(^.className := "mt-3 border border-[#f66] bg-[#fff7f3] p-3",
          <.p(^.className := "mb-2", "退会すると、このアカウントではログインも書き込みもできなくなります。キャップと専ブラ用認証キーも無効になります。元には戻せません。"),
          kind match {
            case "password" => <.div(
              <.label(^.className := "mb-2 block", "パスワード", <.input(^.className := input, ^.`type` := "password", ^.autoComplete := "current-password",
                ^.value := password.value, ^.onChange ==> ((e: ReactEventFromInput) => password.setState(e.target.value)))),
              <.button(^.`type` := "button", ^.className := s"$button text-[#c00]", ^.disabled := busy.value || password.value.isEmpty, ^.onClick --> withdraw, "パスワードを確かめて退会する"))
            case "google" => <.button(^.`type` := "button", ^.className := s"$button text-[#c00]", ^.disabled := busy.value || !AuthClient.flag(raw, "google"),
              ^.onClick --> Callback(startGoogle()), "Googleで確かめて退会する")
            case _ => <.div(
              <.label(^.className := "mb-2 block", <.input(^.`type` := "checkbox", ^.className := "mr-1", ^.checked := agreed.value,
                ^.onChange ==> ((e: ReactEventFromInput) => agreed.setState(e.target.checked))), "退会することを確認しました"),
              <.button(^.`type` := "button", ^.className := s"$button text-[#c00]", ^.disabled := busy.value || !agreed.value, ^.onClick --> withdraw, "退会する"))
          }),
        if message.value.nonEmpty then <.p(^.role := "alert", ^.className := "mt-2 text-[#f00]", message.value) else EmptyVdom)
    }
  }
}
