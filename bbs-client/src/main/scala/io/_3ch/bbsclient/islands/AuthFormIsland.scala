package io._3ch.bbsclient.islands

import japgolly.scalajs.react.*
import japgolly.scalajs.react.vdom.html_<^.*
import org.scalajs.dom
import scala.scalajs.js
import scala.scalajs.js.URIUtils
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.Try

/** Turnstileと規約同意によるかんたん登録 */
object AuthFormIsland {
  private val button = "cursor-pointer border border-[#d6dce3] bg-white px-3 py-2 disabled:cursor-default disabled:opacity-50"
  final val Component = ScalaFnComponent[js.Object] { raw =>
    for {
      agreed <- useState(false)
      token <- useState("")
      busy <- useState(false)
      error <- useState("")
      container <- useRefToVdom[dom.html.Div]
      _ <- useEffectOnMount(CallbackTo {
        var widget: Option[String] = None
        var active = true
        def render(): Unit = if active then container.get.runNow().foreach { el =>
          val turnstile = js.Dynamic.global.selectDynamic("turnstile")
          if !js.isUndefined(turnstile) && widget.isEmpty then {
            val callback: js.Function1[String, Unit] = value => if active then token.setState(value).runNow()
            val expired: js.Function0[Unit] = () => if active then token.setState("").runNow()
            widget = Some(turnstile.render(el, js.Dynamic.literal("sitekey" -> AuthClient.text(raw, "turnstileSiteKey"), "callback" -> callback,
              "expired-callback" -> expired, "error-callback" -> expired)).asInstanceOf[String])
          }
        }
        val script = dom.document.createElement("script").asInstanceOf[dom.html.Script]
        script.src = "https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit"
        script.async = true
        script.onload = _ => render()
        val onError: js.Function1[dom.Event, Unit] = _ => if active then error.setState("確認欄を読み込めませんでした。再読み込みしてください").runNow()
        script.addEventListener("error", onError)
        if !js.isUndefined(js.Dynamic.global.selectDynamic("turnstile")) then render() else dom.document.head.appendChild(script)
        Callback {
          active = false
          widget.foreach(id => Try(js.Dynamic.global.turnstile.remove(id)))
          script.onload = null
          script.removeEventListener("error", onError)
          script.remove()
        }
      })
    } yield {
      def submit(e: ReactEventFromHtml): Callback = e.preventDefaultCB >> Callback {
        if agreed.value && token.value.nonEmpty && !busy.value then {
          busy.setState(true).runNow()
          error.setState("").runNow()
          val params = new dom.URLSearchParams()
          params.set("agreement", AuthClient.text(raw, "agreementVersion"))
          params.set("cf-turnstile-response", token.value)
          params.set("return", AuthClient.text(raw, "returnPath", "/"))
          params.set("device", js.JSON.stringify(BrowserFingerprintCollector.collect()))
          dom.fetch("/auth/verify", new dom.RequestInit { method = dom.HttpMethod.POST; credentials = dom.RequestCredentials.`same-origin`; body = params }).toFuture
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
      }
      if AuthClient.flag(raw, "login") then <.section(^.className := "border border-[#d6dce3] bg-white p-4",
        <.h2(^.className := "mb-3 font-bold", "ログイン"),
        <.label(^.className := "mb-3 block", "ユーザーID", <.input(^.className := "ml-2 border border-[#d6dce3] p-1", ^.autoComplete := "username", ^.disabled := true)),
        <.label(^.className := "mb-3 block", "パスワード", <.input(^.className := "ml-2 border border-[#d6dce3] p-1", ^.`type` := "password", ^.autoComplete := "current-password", ^.disabled := true)),
        <.button(^.`type` := "button", ^.className := button, ^.disabled := true, "ログイン（準備中）"),
        <.p(^.className := "mt-3", <.a(^.className := "text-[#06f]", ^.href := ("/auth/?return=" + URIUtils.encodeURIComponent(AuthClient.text(raw, "returnPath", "/")) + (if AuthClient.flag(raw, "embed") then "&embed=1" else "")), "かんたん登録へ")))
      else <.form(^.className := "border border-[#d6dce3] bg-white p-4", ^.onSubmit ==> submit,
        <.nav(^.className := "mb-4 flex gap-4 border-b border-[#d6dce3] pb-2", <.b("新しく登録する"), <.span(^.className := "text-[#777]", "ログイン（準備中）")),
        <.h2(^.className := "mb-2 font-bold", "登録方法を選ぶ"),
        <.label(^.className := "mb-3 block border border-[#f66] p-3", <.input(^.`type` := "radio", ^.name := "method", ^.checked := true, ^.readOnly := true), " かんたん登録", <.small(^.className := "block text-[#697785]", "この端末だけの仮登録です。Lv1から。1年書き込まないと失効します。")),
        <.div(^.className := "mb-4 flex gap-2", <.button(^.`type` := "button", ^.disabled := true, ^.className := button, "ID・パスワード（準備中）"), <.button(^.`type` := "button", ^.disabled := true, ^.className := button, "Google（準備中）")),
        <.h2(^.className := "mb-2 font-bold", "利用規約と投稿の責任"),
        <.p(^.className := "mb-2 border border-[#d6dce3] bg-[#fafafa] p-3 text-sm", "投稿の責任は投稿者にあります。他人の権利を侵害する投稿や、法令に反する投稿は禁止です。削除・規制のため、接続情報（IPアドレス・回線・端末）を記録します。法令に基づく開示請求に応じることがあります。"),
        <.label(^.className := "mb-4 block", <.input(^.`type` := "checkbox", ^.checked := agreed.value, ^.onChange ==> ((e: ReactEventFromInput) => agreed.setState(e.target.checked))), " 利用規約を読み、投稿内容に責任を持つことに同意します"),
        <.p(^.className := "my-3 text-sm text-[#697785]", "Safari・Chrome・Edge・Firefoxの最新版で開いてください。アプリ内ブラウザやPC版サイトでは認証できないことがあります。"),
        <.div().withRef(container),
        if error.value.nonEmpty then <.p(^.role := "alert", ^.className := "my-2 text-[#f00]", error.value) else EmptyVdom,
        <.button(^.`type` := "submit", ^.className := s"$button border-[#f66]", ^.disabled := (!agreed.value || token.value.isEmpty || busy.value), if busy.value then "認証中…" else "認証して登録する"),
        <.p(^.className := "mt-2 text-xs text-[#697785]", "この端末に認証情報（Cookie）を保存します。次からは認証不要です。"))
    }
  }
}
