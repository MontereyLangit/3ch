package io._3ch.bbsclient.islands

import japgolly.scalajs.react.*
import japgolly.scalajs.react.vdom.html_<^.*
import org.scalajs.dom

import scala.scalajs.js

/** 共通ヘッダー */
object SiteHeaderIsland {
  private val headerClass =
    """site-header sticky top-0 z-10 flex min-h-16 items-center gap-5 border-b-[1.5px] border-[#f66] bg-[#f9f9f9] px-[15px]
      | py-2.5 shadow-[0_1px_4px_#0002] transition-transform duration-200 [&.header-hidden]:-translate-y-full [&.header-hidden]:shadow-none
      | [&_nav]:flex [&_nav]:flex-wrap [&_nav]:items-center [&_nav]:gap-3.5 [&_nav_a]:text-[#333] [&_nav_a]:no-underline max-[767px]:flex-wrap
      | max-[767px]:gap-3 max-[767px]:px-2 max-[767px]:[&_nav]:gap-2 max-[767px]:[&_nav]:text-xs""".stripMargin.replace("\n", "")

  private def focusSearch(e: ReactMouseEvent): Callback = {
    Option(dom.document.getElementById("board-search")) match {
      case Some(input: dom.html.Input) =>
        e.preventDefaultCB >> Callback {
          input.focus(js.Dynamic.literal(preventScroll = true).asInstanceOf[dom.FocusOptions])
          input.asInstanceOf[js.Dynamic].scrollIntoView(js.Dynamic.literal(block = "start"))
        }
      case _ => Callback.empty
    }
  }

  final val Component = ScalaFnComponent[js.Object] { raw =>
    for {
      header <- AutoHideHeader.useAutoHideHeader
    } yield {
      val hiddenClass = if header.hidden then " header-hidden" else ""
      <.header(^.className := s"$headerClass$hiddenClass", ^.onFocus --> header.onFocus,
        ^.onBlur ==> header.onBlur,
        <.a(^.href := "/", ^.className := "site-logo ml-[15px] shrink-0", ^.aria.label := "３ちゃんねる ホーム",
          <.img(^.src := "/static/3ch_logo.png", ^.alt := "３ちゃんねる", ^.className := "inline-block h-[30px] align-middle")),
        <.nav(^.aria.label := "メインメニュー",
          <.a(^.href := "/", "ホーム"), <.span("|"), <.a(^.href := "/bbsmenu.html", "板一覧"), <.span("|"),
          <.a(^.href := "#board-search", ^.onClick ==> focusSearch, "検索")),
        if AuthClient.flag(raw, "authEnabled") then <.nav(^.className := "account-nav ml-auto", ^.aria.label := "アカウント", AccountNavIsland.Component(raw)) else
          <.nav(^.className := "account-nav ml-auto", ^.aria.label := "アカウント",
          <.span(^.title := "準備中", "認証・登録"), <.span("|"), <.span(<.span(^.title := "準備中", "ログイン"),
            <.button(^.`type` := "button", ^.className := "dropBtnSetting settingDrop cursor-pointer border-0 bg-transparent p-0", ^.title := "準備中",
              ^.aria.label := "アカウントメニュー", VdomAttr("aria-expanded") := "false",
              <.img(^.src := "/static/icon_extend.png", ^.alt := "", ^.className := "dropBtnSetting ml-[5px] mb-[2px] inline"))))
      ).withRef(header.ref)
    }
  }
  final val JsComponent = Component
}
