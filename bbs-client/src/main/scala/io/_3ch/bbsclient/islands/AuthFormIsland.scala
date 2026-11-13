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
    val upgrade = AuthClient.flag(raw, "upgrade")
    val form = dom.document.createElement("form").asInstanceOf[dom.html.Form]
    form.method = "post"
    form.action = "/auth/google/start"
    form.target = if AuthClient.flag(raw, "embed") then "_blank" else "_self"
    val values = Seq("uid" -> uid, "return" -> AuthClient.text(raw, "returnPath", "/"), "popup" -> (if AuthClient.flag(raw, "embed") then "1" else "0"),
      "device" -> js.JSON.stringify(BrowserFingerprintCollector.collect())) ++
      (if agreed then Seq("agreement" -> AuthClient.text(raw, "agreementVersion")) else Nil) ++
      (if upgrade then Seq("purpose" -> "upgrade") else Nil)
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
      login <- useState(AuthClient.flag(raw, "login") && !AuthClient.flag(raw, "upgrade"))
      chosen <- useState(if AuthClient.flag(raw, "upgrade") then AuthMethod.Password else AuthMethod.Client)
      agreed <- useState(false)
      userId <- useState("")
      password <- useState("")
      confirmation <- useState("")
      email <- useState("")
      forgot <- useState(false)
      notice <- useState("")
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
      val mail = AuthClient.flag(raw, "mailEnabled")
      val upgrade = AuthClient.flag(raw, "upgrade")
      val registering = !login.value
      val choice = chosen.value
      def resetTurnstile(): Unit = {
        token.setState("").runNow()
        Try(js.Dynamic.global.turnstile.reset())
      }
      def requestReset(): Unit = {
        busy.setState(true).runNow()
        error.setState("").runNow()
        AuthClient.post("/auth/reset/request", Seq("id" -> userId.value, "cf-turnstile-response" -> token.value,
          "device" -> js.JSON.stringify(BrowserFingerprintCollector.collect()))).map { case (ok, json) =>
          busy.setState(false).runNow()
          if ok then notice.setState("メール認証済みのアカウントなら、登録したアドレスに再設定のメールを送りました。届かないときは管理者にお問い合わせください。").runNow()
          else error.setState(AuthClient.error(json, "送信できませんでした。もう一度お試しください")).runNow()
          resetTurnstile()
        }.recover { case _ => busy.setState(false).runNow(); error.setState("通信できませんでした。もう一度お試しください").runNow() }
      }
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
        else if forgot.value then token.value.nonEmpty && userId.value.nonEmpty && !busy.value
        else token.value.nonEmpty && userId.value.nonEmpty && password.value.nonEmpty && !busy.value
      def submit(e: ReactEventFromHtml): Callback = e.preventDefaultCB >> Callback {
        if ready then {
          if !registering && forgot.value then requestReset()
          else if !registering then post("/auth/login", Seq("id" -> userId.value, "password" -> password.value))
          else choice match {
            case AuthMethod.Client => post("/auth/verify", Seq("agreement" -> AuthClient.text(raw, "agreementVersion")))
            case AuthMethod.Password => post("/auth/register", Seq("agreement" -> AuthClient.text(raw, "agreementVersion"), "id" -> userId.value,
              "password" -> password.value, "password2" -> confirmation.value) ++
              (if mail && email.value.trim.nonEmpty then Seq("email" -> email.value.trim) else Nil) ++ (if upgrade then Seq("upgrade" -> "1") else Nil))
            case AuthMethod.Google => startGoogle(raw, userId.value, agreed = true)
          }
        }
      }
      def tab(label: String, active: Boolean, toLogin: Boolean) =
        <.button(^.`type` := "button", ^.role := "tab", ^.aria.selected := active,
          ^.className := (if active then "cursor-pointer border-b-2 border-[#f66] pb-1 font-bold" else "cursor-pointer pb-1 text-[#06f]"),
          ^.onClick --> (login.setState(toLogin) >> forgot.setState(false) >> notice.setState("") >> error.setState("")), label)
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
        if upgrade then EmptyVdom else <.nav(^.className := "mb-4 flex gap-4 border-b border-[#d6dce3]", ^.role := "tablist", ^.aria.label := "登録・ログイン",
          tab("新しく登録する", registering, toLogin = false), tab("ログイン", !registering, toLogin = true)),
        if registering then <.div(^.key := "register",
          <.h2(^.className := "mb-2 font-bold", if upgrade then "切り替える方法を選ぶ" else "登録方法を選ぶ"),
          <.div(^.role := "radiogroup", ^.className := "mb-4",
            if upgrade then EmptyVdom else option(AuthMethod.Client, "かんたん登録", "この端末だけの仮登録です。Lv1から。1年書き込まないと失効します。"),
            option(AuthMethod.Password, "ID・パスワード", if mail then "ほかの端末からもログインできます。Lv1から、メール認証でLv3。" else "ほかの端末からもログインできます。Lv1から。"),
            option(AuthMethod.Google, if google then "Googleで続ける" else "Googleで続ける（準備中）", "Googleアカウントで登録します。Lv10。", google)),
          if choice != AuthMethod.Client then field("ユーザーID", idHint, userId.value, userId.setState, "text", "username") else EmptyVdom,
          if choice == AuthMethod.Password then <.div(
            field("パスワード", "（8文字以上）", password.value, password.setState, "password", "new-password"),
            field("パスワード（確認）", "", confirmation.value, confirmation.setState, "password", "new-password"),
            if mail then field("メールアドレス", "（任意。認証するとLv3から始まり、パスワードを再設定できます）", email.value, email.setState, "email", "email")
            else <.p(^.className := "mb-3 text-xs text-[#697785]", "メールアドレスの登録（認証でLv3から）は準備中です。")) else EmptyVdom,
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
        else if forgot.value then <.div(^.key := "forgot",
          <.h2(^.className := "mb-2 font-bold", "パスワードの再設定"),
          <.p(^.className := "mb-3", "メール認証済みのアカウントだけ、登録したアドレスに再設定のメールを送ります。削除・規制などの権限があるアカウントは管理者にお問い合わせください。"),
          field("ユーザーID", "", userId.value, userId.setState, "text", "username"))
        else <.div(^.key := "login",
          field("ユーザーID", "", userId.value, userId.setState, "text", "username"),
          field("パスワード", "", password.value, password.setState, "password", "current-password")),
        <.div(^.key := "turnstile", ^.hidden := (registering && choice == AuthMethod.Google),
          if registering then <.h2(^.className := "mb-2 font-bold", "人間であることの確認") else EmptyVdom,
          <.div(^.className := "mb-3").withRef(container)),
        if error.value.nonEmpty then <.p(^.role := "alert", ^.className := "my-2 text-[#f00]", error.value) else EmptyVdom,
        if notice.value.nonEmpty then <.p(^.role := "status", ^.className := "my-2", notice.value) else EmptyVdom,
        <.button(^.`type` := "submit", ^.className := s"$button border-[#f66]", ^.disabled := !ready,
          if busy.value then "認証中…"
          else if !registering && forgot.value then "再設定のメールを送る"
          else if !registering then "ログイン"
          else if choice == AuthMethod.Google then (if upgrade then "Googleに切り替える" else "Googleで登録する")
          else if upgrade then "ID・パスワードに切り替える"
          else "認証して登録する"),
        if !registering && !forgot.value then <.button(^.`type` := "button", ^.className := s"$button ml-2", ^.disabled := !google,
          ^.onClick --> Callback(startGoogle(raw, "", agreed = false)), if google then "Googleでログイン" else "Googleでログイン（準備中）")
        else EmptyVdom,
        if !registering then <.p(^.className := "mt-3 text-xs",
          if forgot.value then <.button(^.`type` := "button", ^.className := "cursor-pointer text-[#06f]", ^.onClick --> (forgot.setState(false) >> notice.setState("") >> error.setState("")), "ログインに戻る")
          else if mail then <.button(^.`type` := "button", ^.className := "cursor-pointer text-[#06f]", ^.onClick --> (forgot.setState(true) >> notice.setState("") >> error.setState("")), "パスワードを忘れた場合")
          else <.span(^.className := "text-[#697785]", "パスワードの再設定（準備中）")) else EmptyVdom,
        if registering || !forgot.value then <.p(^.className := "mt-2 text-xs text-[#697785]",
          if registering && choice == AuthMethod.Client then "この端末に認証情報（Cookie）を保存します。次からは認証不要です。"
          else "この端末に認証情報（Cookie）を保存します。") else EmptyVdom)
    }
  }
}
