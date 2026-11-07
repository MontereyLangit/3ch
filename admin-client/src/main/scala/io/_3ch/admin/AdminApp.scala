package io._3ch.admin

import io._3ch.admin.api.AdminApiClient
import io._3ch.admin.components.{Layout, Link, Styles}
import io._3ch.admin.pages.{BoardSettingsPage, TopPage}
import io._3ch.common.admin.AdminCodec
import japgolly.scalajs.react.*
import japgolly.scalajs.react.vdom.html_<^.*
import org.scalajs.dom

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

/** 管理画面の全体。URL と画面の対応を持ち、未保存の変更があれば離れる前に確認する */
object AdminApp {
  val LeaveMessage = "保存していない変更があります。このページを離れますか？"

  final val Component = ScalaFnComponent[Unit] { _ =>
    for {
      page <- useState(AdminPage.parse(dom.window.location.pathname))
      dirty <- useState(false)
      boards <- Loaded.useLoad("boards")(() => AdminApiClient.get("/boards/")(AdminCodec.boardList).map(_.map(_.boards)))
      _ <- useEffectWithDeps((page.value.path, dirty.value)) { (current, unsaved) =>
        CallbackTo {
          val onPop: js.Function1[dom.PopStateEvent, Unit] = _ => {
            if (unsaved && !dom.window.confirm(LeaveMessage)) dom.window.history.pushState(null, "", current)
            else (page.setState(AdminPage.parse(dom.window.location.pathname)) >> dirty.setState(false)).runNow()
          }
          val onUnload: js.Function1[dom.BeforeUnloadEvent, Unit] = e => {
            if (unsaved) {
              e.preventDefault()
              e.returnValue = ""
            }
          }
          dom.window.addEventListener("popstate", onPop)
          dom.window.addEventListener("beforeunload", onUnload)
          Callback {
            dom.window.removeEventListener("popstate", onPop)
            dom.window.removeEventListener("beforeunload", onUnload)
          }
        }
      }
      _ <- useEffectWithDeps(page.value.title)(title => Callback(dom.document.title = title))
    } yield {
      val go = (next: AdminPage) => Callback {
        if (!dirty.value || dom.window.confirm(LeaveMessage)) {
          if (next.path != page.value.path) dom.window.history.pushState(null, "", next.path)
          (page.setState(next) >> dirty.setState(false)).runNow()
          dom.window.scrollTo(0, 0)
        }
      }
      val nav = Nav(page.value, go, dirty.setState)
      val content: VdomNode = page.value match {
        case AdminPage.Top => TopPage.Component(TopPage.Props(nav, boards.state))
        case AdminPage.Board(bbs) => BoardSettingsPage.Component(BoardSettingsPage.Props(nav, bbs))
        case _ => <.div(<.h1(^.className := Styles.h1, "ページがありません"),
          <.p(Link(nav, AdminPage.Top, Styles.link, "管理画面のトップへ")))
      }
      Layout(nav, boards.state, <.div(^.key := page.value.path, content))
    }
  }
}
