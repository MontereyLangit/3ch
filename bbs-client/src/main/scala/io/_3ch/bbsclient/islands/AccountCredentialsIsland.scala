package io._3ch.bbsclient.islands

import japgolly.scalajs.react.*
import japgolly.scalajs.react.vdom.html_<^.*
import scala.scalajs.js
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

/** ID・パスワードのアカウントのメールアドレスとパスワードの変更 */
object AccountCredentialsIsland {
  import AccountPostView.button
  private val input = "mt-1 block w-full max-w-sm border border-[#d6dce3] p-1"
  private val section = "my-4 border border-[#d6dce3] bg-white p-4"

  final val Component = ScalaFnComponent[js.Object] { raw =>
    for {
      email <- useState("")
      pending <- useState(AuthClient.text(raw, "pendingEmail"))
      mailCurrent <- useState("")
      current <- useState("")
      password <- useState("")
      confirmation <- useState("")
      busy <- useState(false)
      mailMessage <- useState("")
      passwordMessage <- useState("")
    } yield {
      val enabled = AuthClient.flag(raw, "mailEnabled")
      val verified = AuthClient.text(raw, "email")
      def send(path: String, fields: Seq[(String, String)], report: String => Callback)(success: js.Object => Callback): Callback =
        busy.setState(true) >> report("") >> Callback {
          AuthClient.post(path, fields).map { case (ok, json) =>
            busy.setState(false).runNow()
            (if ok then success(json) else report(AuthClient.error(json))).runNow()
          }.recover { case _ => busy.setState(false).runNow(); report("通信できませんでした").runNow() }
        }
      val requestMail = send("/auth/email", Seq("email" -> email.value.trim, "current" -> mailCurrent.value), mailMessage.setState) { json =>
        pending.setState(AuthClient.text(json, "email")) >> email.setState("") >> mailCurrent.setState("") >> mailMessage.setState("確認メールを送りました。メールのリンクを開くと登録が完了します。")
      }
      val changePassword = send("/auth/password", Seq("current" -> current.value, "password" -> password.value, "password2" -> confirmation.value),
        passwordMessage.setState) { _ =>
        current.setState("") >> password.setState("") >> confirmation.setState("") >> passwordMessage.setState("パスワードを変更しました。ほかの端末はログアウトしました。")
      }
      def field(label: String, value: String, set: String => Callback, kind: String, complete: String) =
        <.label(^.className := "mb-3 block", label, <.input(^.className := input, ^.`type` := kind, ^.autoComplete := complete, ^.value := value,
          ^.onChange ==> ((e: ReactEventFromInput) => set(e.target.value))))
      <.div(
        <.section(^.id := "email", ^.className := section, <.h2(^.className := "mb-2 font-bold", "■ メールアドレス"),
          <.p(^.className := "mb-2", if verified.nonEmpty then s"$verified（確認済み）" else "未登録"),
          if pending.value.nonEmpty then <.p(^.className := "mb-2 text-xs text-[#697785]", s"${pending.value} の確認待ちです。メールのリンクを開くと切り替わります。") else EmptyVdom,
          if !enabled then <.p(^.className := "text-[#697785]", "メールアドレスの登録は準備中です。")
          else <.div(
            <.p(^.className := "mb-2 text-xs text-[#697785]", "確認するとLv3から始まり、パスワードを忘れたときに再設定できます。"),
            <.div(^.className := "flex flex-wrap items-end gap-2",
              <.label(^.className := "block", "新しいメールアドレス", <.input(^.className := input, ^.`type` := "email", ^.autoComplete := "email",
                ^.value := email.value, ^.onChange ==> ((e: ReactEventFromInput) => email.setState(e.target.value)))),
              field("いまのパスワード", mailCurrent.value, mailCurrent.setState, "password", "current-password"),
              <.button(^.`type` := "button", ^.className := button, ^.disabled := busy.value || email.value.trim.isEmpty || mailCurrent.value.isEmpty, ^.onClick --> requestMail, "確認メールを送る"))),
          if mailMessage.value.nonEmpty then <.p(^.role := "status", ^.className := "mt-2", mailMessage.value) else EmptyVdom),
        <.section(^.id := "password", ^.className := section, <.h2(^.className := "mb-2 font-bold", "■ パスワードの変更"),
          field("いまのパスワード", current.value, current.setState, "password", "current-password"),
          field("新しいパスワード（8文字以上）", password.value, password.setState, "password", "new-password"),
          field("新しいパスワード（確認）", confirmation.value, confirmation.setState, "password", "new-password"),
          <.button(^.`type` := "button", ^.className := button,
            ^.disabled := busy.value || current.value.isEmpty || password.value.isEmpty || confirmation.value.isEmpty, ^.onClick --> changePassword, "パスワードを変更する"),
          <.p(^.className := "mt-2 text-xs text-[#697785]", "変更すると、この端末以外はログアウトします。"),
          if passwordMessage.value.nonEmpty then <.p(^.role := "status", ^.className := "mt-2", passwordMessage.value) else EmptyVdom))
    }
  }
}
