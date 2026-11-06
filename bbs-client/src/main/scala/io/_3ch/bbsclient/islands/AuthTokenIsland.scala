package io._3ch.bbsclient.islands

import japgolly.scalajs.react.*
import japgolly.scalajs.react.vdom.html_<^.*
import scala.scalajs.js
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

/** メールのリンクの先。確認と再設定はボタンを押してから送り、リンクの先読みでトークンを使わない */
object AuthTokenIsland {
  import AccountPostView.button
  private val input = "mt-1 block w-full max-w-sm border border-[#d6dce3] p-1"

  final val Component = ScalaFnComponent[js.Object] { raw =>
    for {
      password <- useState("")
      confirmation <- useState("")
      busy <- useState(false)
      done <- useState("")
      message <- useState("")
    } yield {
      val reset = AuthClient.text(raw, "mode") == "reset"
      val token = AuthClient.text(raw, "token")
      def send(path: String, fields: Seq[(String, String)])(success: js.Object => String): Callback =
        busy.setState(true) >> message.setState("") >> Callback {
          AuthClient.post(path, fields :+ ("token" -> token)).map { case (ok, json) =>
            busy.setState(false).runNow()
            if ok then done.setState(success(json)).runNow() else message.setState(AuthClient.error(json)).runNow()
          }.recover { case _ => busy.setState(false).runNow(); message.setState("通信できませんでした。もう一度お試しください").runNow() }
        }
      val submit: ReactEventFromHtml => Callback = e => e.preventDefaultCB >> (
        if reset then send("/auth/reset", Seq("password" -> password.value, "password2" -> confirmation.value))(_ =>
          "パスワードを変更しました。新しいパスワードでログインしてください。")
        else send("/auth/email/verify", Nil)(json =>
          s"メールアドレスを確認しました。いまのLvは${AuthClient.session(json).fold(3)(_.lv)}です。"))
      def field(label: String, value: String, set: String => Callback) =
        <.label(^.className := "mb-3 block", label,
          <.input(^.className := input, ^.`type` := "password", ^.autoComplete := "new-password", ^.value := value,
            ^.onChange ==> ((e: ReactEventFromInput) => set(e.target.value))))
      if !AuthClient.flag(raw, "enabled") then <.p(^.className := "border border-[#d6dce3] bg-white p-4", "メールによる認証は準備中です。")
      else if token.isEmpty then <.p(^.role := "alert", ^.className := "border border-[#d6dce3] bg-white p-4 text-[#f00]", "リンクが無効か、期限が切れています。")
      else if done.value.nonEmpty then <.div(^.className := "border border-[#d6dce3] bg-white p-4", <.p(^.role := "status", done.value),
        <.p(^.className := "mt-3", <.a(^.className := "text-[#06f] hover:underline", ^.href := (if reset then "/auth/?mode=login" else "/auth/account/"),
          if reset then "ログインする" else "アカウント設定へ")))
      else <.form(^.className := "border border-[#d6dce3] bg-white p-4", ^.onSubmit ==> submit,
        if reset then <.div(field("新しいパスワード（8文字以上）", password.value, password.setState), field("新しいパスワード（確認）", confirmation.value, confirmation.setState))
        else <.p(^.className := "mb-3", "このメールアドレスをアカウントに登録します。"),
        if message.value.nonEmpty then <.p(^.role := "alert", ^.className := "my-2 text-[#f00]", message.value) else EmptyVdom,
        <.button(^.`type` := "submit", ^.className := s"$button border-[#f66]",
          ^.disabled := busy.value || (reset && (password.value.isEmpty || confirmation.value.isEmpty)),
          if reset then "パスワードを変更する" else "確認する"))
    }
  }
}
