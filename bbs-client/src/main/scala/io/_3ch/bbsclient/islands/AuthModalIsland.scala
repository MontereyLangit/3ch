package io._3ch.bbsclient.islands

import japgolly.scalajs.react.*
import japgolly.scalajs.react.vdom.html_<^.*
import japgolly.scalajs.react.vdom.HtmlTagOf
import org.scalajs.dom
import scala.scalajs.js
import scala.scalajs.js.URIUtils
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.Try

/** 投稿を中断せずに認証するダイアログ */
object AuthModalIsland {
  final val Component = ScalaFnComponent[js.Object] { _ =>
    for {
      url <- useState(Option.empty[String])
      dialog <- useRefToVdom[dom.html.Element]
      frame <- useRefToVdom[dom.html.IFrame]
      finish <- useRef(Option.empty[js.Function1[Boolean, Unit]])
      cleanup <- useRef[() => Unit](() => ())
      _ <- useEffectOnMount(CallbackTo {
        def close(ok: Boolean): Unit = {
          cleanup.value()
          cleanup.value = () => ()
          dialog.get.runNow().foreach(d => { Try(d.asInstanceOf[js.Dynamic].close()); d.removeAttribute("open") })
          val resolve = finish.value
          finish.value = None
          url.setState(None).runNow()
          resolve.foreach(_(ok))
        }
        val open: js.Function1[dom.Event, Unit] = e => {
          close(false)
          val detail = e.asInstanceOf[js.Dynamic].detail
          detail.updateDynamic("handled")(true)
          finish.value = Some(detail.resolve.asInstanceOf[js.Function1[Boolean, Unit]])
          url.setState(Some("/auth/?embed=1&return=" + URIUtils.encodeURIComponent(dom.window.location.pathname + dom.window.location.search))).runNow()
          var active = true
          var checking = false
          def recheck(): Unit = if active && !checking then {
            checking = true
            AuthClient.readSession().foreach { session =>
              checking = false
              if active then session.foreach { s => AuthClient.changed(Some(s)); close(true) }
            }
          }
          val message: js.Function1[dom.MessageEvent, Unit] = event => {
            val data = event.data
            if event.origin == dom.window.location.origin && frame.get.runNow().exists(f => event.source == f.contentWindow) &&
              data != null && js.typeOf(data) == "object" && data.asInstanceOf[js.Dynamic].selectDynamic("type").asInstanceOf[js.UndefOr[String]].contains("ch3s-auth") &&
              data.asInstanceOf[js.Dynamic].selectDynamic("status").asInstanceOf[js.UndefOr[String]].contains("ok") then recheck()
          }
          dom.window.addEventListener("message", message)
          val channel = Try(js.Dynamic.newInstance(js.Dynamic.global.BroadcastChannel)("ch3s-auth")).toOption
          channel.foreach(_.updateDynamic("onmessage")(((_: js.Any) => recheck()): js.Function1[js.Any, Unit]))
          val interval = dom.window.setInterval(() => recheck(), 2000)
          val timeout = dom.window.setTimeout(() => close(false), 300000)
          val cancel: js.Function1[dom.Event, Unit] = _ => close(false)
          dom.window.addEventListener("ch3s:auth-cancel", cancel)
          cleanup.value = () => {
            active = false
            dom.window.clearInterval(interval)
            dom.window.clearTimeout(timeout)
            dom.window.removeEventListener("message", message)
            dom.window.removeEventListener("ch3s:auth-cancel", cancel)
            channel.foreach(_.close())
          }
        }
        val onCancel: js.Function1[dom.Event, Unit] = event => { event.preventDefault(); close(false) }
        dialog.get.runNow().foreach(_.addEventListener("cancel", onCancel))
        dom.window.addEventListener("ch3s:auth-open", open)
        Callback {
          dialog.get.runNow().foreach(_.removeEventListener("cancel", onCancel))
          dom.window.removeEventListener("ch3s:auth-open", open)
          close(false)
        }
      })
      _ <- useEffect(Callback {
        if url.value.isDefined then dialog.get.runNow().foreach { d =>
          if !d.hasAttribute("open") then Try(d.asInstanceOf[js.Dynamic].showModal()).recover { case _ => d.setAttribute("open", "") }
        }
      })
    } yield {
      HtmlTagOf[dom.html.Element]("dialog")(^.className := "fixed inset-0 m-auto w-[min(680px,94vw)] max-w-none border border-[#d6dce3] bg-[#fafafa] p-0 text-[#333] shadow-lg backdrop:bg-black/40",
        ^.aria.label := "認証・登録",
        <.div(^.className := "flex items-center justify-between border-b border-[#f66] p-3", <.b("認証・登録"),
          <.button(^.`type` := "button", ^.className := "cursor-pointer border border-[#d6dce3] bg-white px-2 py-1", ^.onClick --> Callback(AuthClient.event("ch3s:auth-cancel", js.Dynamic.literal())), "閉じる")),
        url.value.toVdomArray(src => <.iframe(^.key := src, ^.src := src, ^.title := "認証・登録", ^.className := "h-[75vh] w-full border-0").withRef(frame))).withRef(dialog)
    }
  }
}
