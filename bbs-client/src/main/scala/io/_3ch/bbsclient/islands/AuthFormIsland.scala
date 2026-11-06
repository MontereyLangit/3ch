package io._3ch.bbsclient.islands

import japgolly.scalajs.react.*
import japgolly.scalajs.react.vdom.html_<^.*
import org.scalajs.dom
import scala.scalajs.js
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.Try

/** 登録方法。かんたん登録は Turnstile と同意だけで済む */
private enum AuthMethod {
  case Client
  case Password
  case Google
}

/** 認証・登録とログイン。Turnstile のウィジェットは両方のタブで同じものを使う */
object AuthFormIsland {
  private val button = "cursor-pointer border border-[#d6dce3] bg-white px-3 py-2 disabled:cursor-default disabled:opacity-50"
  private val input = "mt-1 block w-full max-w-sm border border-[#d6dce3] p-1"
  private val box = "mb-4 border border-[#d6dce3] bg-[#fafafa] p-3 text-sm"

  /** Google の開始はフォームの遷移で送る。埋め込み時は別のウィンドウで開く */
  private def startGoogle(raw: js.Object, uid: String, agreed: Boolean): Unit = {
    val form = dom.document.createElement("form").asInstanceOf[dom.html.Form]
    form.method = "post"
    form.action = "/auth/google/start"
    form.target = if AuthClient.flag(raw, "embed") then "_blank" else "_self"
    val values = Seq("uid" -> uid, "return" -> AuthClient.text(raw, "returnPath", "/"), "popup" -> (if AuthClient.flag(raw, "embed") then "1" else "0"),
      "device" -> js.JSON.stringify(BrowserFingerprintCollector.collect())) ++
      (if agreed then Seq("agreement" -> AuthClient.text(raw, "agreementVersion")) else Nil)
    values.foreach { case (name, value) =>
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
      login <- useState(AuthClient.flag(raw, "login"))
      chosen <- useState(AuthMethod.Client)
      agreed <- useState(false)
      userId <- useState("")
      password <- useState("")
      confirmation <- useState("")
      token <- useState("")
      busy <- useState(false)
      error <- useState(AuthClient.text(raw, "notice"))
      container <- useRefToVdom[dom.html.Div]
      widget <- useRef(Option.empty[String])
      mounted <- useRef(false)
      _ <- useEffectOnMount(CallbackTo {
        mounted.value = true
        def render(): Unit = if mounted.value then container.get.runNow().foreach { el =>
          val turnstile = js.Dynamic.global.selectDynamic("turnstile")
          if !js.isUndefined(turnstile) && widget.value.isEmpty then {
            val callback: js.Function1[String, Unit] = value => if mounted.value then token.setState(value).runNow()
            val expired: js.Function0[Unit] = () => if mounted.value then token.setState("").runNow()
            widget.value = Some(turnstile.render(el, js.Dynamic.literal("sitekey" -> AuthClient.text(raw, "turnstileSiteKey"), "callback" -> callback,
              "expired-callback" -> expired, "error-callback" -> expired)).asInstanceOf[String])
          }
        }
        val script = dom.document.createElement("script").asInstanceOf[dom.html.Script]
        script.src = "https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit"
        script.async = true
        script.onload = _ => render()
        val onError: js.Function1[dom.Event, Unit] = _ => if mounted.value then error.setState("確認欄を読み込めませんでした。再読み込みしてください").runNow()
        script.addEventListener("error", onError)
        if !js.isUndefined(js.Dynamic.global.selectDynamic("turnstile")) then render() else dom.document.head.appendChild(script)
        Callback {
          mounted.value = false
          widget.value.foreach(id => Try(js.Dynamic.global.turnstile.remove(id)))
          widget.value = None
          script.onload = null
          script.removeEventListener("error", onError)
          script.remove()
        }
      })
    } yield {
      val google = AuthClient.flag(raw, "googleEnabled")
      val registering = !login.value
      val choice = chosen.value
      def post(path: String, values: Seq[(String, String)]): Unit = {
        busy.setState(true).runNow()
        error.setState("").runNow()
        val params = new dom.URLSearchParams()
        (values ++ Seq("cf-turnstile-response" -> token.value, "return" -> AuthClient.text(raw, "returnPath", "/"),
          "device" -> js.JSON.stringify(BrowserFingerprintCollector.collect()))).foreach { case (name, value) => params.set(name, value) }
        dom.fetch(path, new dom.RequestInit { method = dom.HttpMethod.POST; credentials = dom.RequestCredentials.`same-origin`; body = params }).toFuture
          .flatMap(r => r.json().toFuture.map(json => (r.ok, json))).map { case (ok, json) =>
            busy.setState(false).runNow()
            AuthClient.session(json) match {
              case Some(s) if ok =>
                val message = js.Dynamic.literal(`type` = "ch3s-auth", status = "ok", uid = s.uid, lv = s.lv)
                if AuthClient.flag(raw, "embed") then dom.window.parent.postMessage(message, dom.window.location.origin)
                else dom.window.location.assign(AuthClient.text(raw, "returnPath", "/"))
              case _ =>
                error.setState(AuthClient.text(json.asInstanceOf[js.Object], "error", "認証できませんでした。もう一度お試しください")).runNow()
                token.setState("").runNow()
                Try(js.Dynamic.global.turnstile.reset())
            }
          }
          .recover { case _ => busy.setState(false).runNow(); error.setState("通信できませんでした。もう一度お試しください").runNow() }
      }
      val ready =
        if registering then agreed.value && !busy.value && (choice match {
          case AuthMethod.Client => token.value.nonEmpty
          case AuthMethod.Password => token.value.nonEmpty && userId.value.nonEmpty && password.value.nonEmpty && confirmation.value.nonEmpty
          case AuthMethod.Google => google && userId.value.nonEmpty
        })
        else token.value.nonEmpty && userId.value.nonEmpty && password.value.nonEmpty && !busy.value
      def submit(e: ReactEventFromHtml): Callback = e.preventDefaultCB >> Callback {
        if ready then {
          if !registering then post("/auth/login", Seq("id" -> userId.value, "password" -> password.value))
          else choice match {
            case AuthMethod.Client => post("/auth/verify", Seq("agreement" -> AuthClient.text(raw, "agreementVersion")))
            case AuthMethod.Password => post("/auth/register", Seq("agreement" -> AuthClient.text(raw, "agreementVersion"), "id" -> userId.value,
              "password" -> password.value, "password2" -> confirmation.value))
            case AuthMethod.Google => startGoogle(raw, userId.value, agreed = true)
          }
        }
      }
      def tab(label: String, active: Boolean, toLogin: Boolean) =
        <.button(^.`type` := "button", ^.role := "tab", ^.aria.selected := active,
          ^.className := (if active then "cursor-pointer border-b-2 border-[#f66] pb-1 font-bold" else "cursor-pointer pb-1 text-[#06f]"),
          ^.onClick --> (login.setState(toLogin) >> error.setState("")), label)
      def option(value: AuthMethod, title: String, note: String, enabled: Boolean = true) =
        <.label(^.className := s"mb-2 block border p-3 ${if choice == value then "border-[#f66]" else "border-[#d6dce3]"}${if enabled then " cursor-pointer" else " text-[#999]"}",
          <.input(^.`type` := "radio", ^.name := "method", ^.checked := (choice == value), ^.disabled := !enabled,
            ^.onChange --> (chosen.setState(value) >> error.setState(""))), " ", <.b(title), <.small(^.className := "block text-[#697785]", note))
      def field(label: String, hint: String, value: String, set: String => Callback, kind: String, complete: String) =
        <.label(^.className := "mb-3 block", label, if hint.nonEmpty then <.small(^.className := "text-[#697785]", hint) else EmptyVdom,
          <.input(^.className := input, ^.`type` := kind, ^.autoComplete := complete, ^.value := value,
            ^.onChange ==> ((e: ReactEventFromInput) => set(e.target.value))))
      val idHint = "（半角英小文字・数字・_、3〜16文字。名前欄に!idと書くと表示されます）"
      <.form(^.className := "border border-[#d6dce3] bg-white p-4", ^.onSubmit ==> submit,
        <.nav(^.className := "mb-4 flex gap-4 border-b border-[#d6dce3]", ^.role := "tablist", ^.aria.label := "登録・ログイン",
          tab("新しく登録する", registering, toLogin = false), tab("ログイン", !registering, toLogin = true)),
        if registering then <.div(^.key := "register",
          <.h2(^.className := "mb-2 font-bold", "登録方法を選ぶ"),
          <.div(^.role := "radiogroup", ^.className := "mb-4",
            option(AuthMethod.Client, "かんたん登録", "この端末だけの仮登録です。Lv1から。1年書き込まないと失効します。"),
            option(AuthMethod.Password, "ID・パスワード", "ほかの端末からもログインできます。Lv1から。"),
            option(AuthMethod.Google, if google then "Googleで続ける" else "Googleで続ける（準備中）", "Googleアカウントで登録します。Lv10。", google)),
          if choice != AuthMethod.Client then field("ユーザーID", idHint, userId.value, userId.setState, "text", "username") else EmptyVdom,
          if choice == AuthMethod.Password then <.div(
            field("パスワード", "（8文字以上）", password.value, password.setState, "password", "new-password"),
            field("パスワード（確認）", "", confirmation.value, confirmation.setState, "password", "new-password")) else EmptyVdom,
          <.h2(^.className := "mb-2 font-bold", "利用規約と投稿の責任"),
          <.p(^.className := "mb-2 border border-[#d6dce3] bg-[#fafafa] p-3 text-sm", "投稿の責任は投稿者にあります。他人の権利を侵害する投稿や、法令に反する投稿は禁止です。削除・規制のため、接続情報（IPアドレス・回線・端末）を記録します。法令に基づく開示請求に応じることがあります。"),
          <.label(^.className := "mb-4 block", <.input(^.`type` := "checkbox", ^.checked := agreed.value, ^.onChange ==> ((e: ReactEventFromInput) => agreed.setState(e.target.checked))), " 利用規約を読み、投稿内容に責任を持つことに同意します"),
          <.h2(^.className := "mb-2 font-bold", "認証の前にお読みください"),
          <.div(^.className := box,
            <.p(<.b("推奨環境"), "：Safari・Chrome・Edge・Firefoxの最新版"),
            <.p(^.className := "mt-2", <.b("認証できないとき")),
            <.ul(^.className := "ml-5 list-disc",
              <.li("専ブラの内蔵ブラウザ → このページを推奨ブラウザで開き直す"),
              <.li("X・LINE・Discordなどのアプリ内ブラウザ →「ブラウザで開く」"),
              <.li("「PC版サイト」「デスクトップ表示」→ オフにして再読み込み"),
              <.li("UAを変える拡張機能 → 切って再読み込み"))))
        else <.div(^.key := "login",
          field("ユーザーID", "", userId.value, userId.setState, "text", "username"),
          field("パスワード", "", password.value, password.setState, "password", "current-password")),
        <.div(^.key := "turnstile", ^.hidden := (registering && choice == AuthMethod.Google),
          if registering then <.h2(^.className := "mb-2 font-bold", "人間であることの確認") else EmptyVdom,
          <.div(^.className := "mb-3").withRef(container)),
        if error.value.nonEmpty then <.p(^.role := "alert", ^.className := "my-2 text-[#f00]", error.value) else EmptyVdom,
        <.button(^.`type` := "submit", ^.className := s"$button border-[#f66]", ^.disabled := !ready,
          if busy.value then "認証中…"
          else if !registering then "ログイン"
          else if choice == AuthMethod.Google then "Googleで登録する"
          else "認証して登録する"),
        if !registering then <.button(^.`type` := "button", ^.className := s"$button ml-2", ^.disabled := !google,
          ^.onClick --> Callback(startGoogle(raw, "", agreed = false)), if google then "Googleでログイン" else "Googleでログイン（準備中）")
        else EmptyVdom,
        <.p(^.className := "mt-2 text-xs text-[#697785]",
          if registering && choice == AuthMethod.Client then "この端末に認証情報（Cookie）を保存します。次からは認証不要です。"
          else "この端末に認証情報（Cookie）を保存します。"))
    }
  }
}
