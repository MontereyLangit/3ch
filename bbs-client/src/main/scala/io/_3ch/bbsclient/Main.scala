package io._3ch.bbsclient

import io._3ch.bbsclient.islands.*
import io._3ch.common.routes.islands
import japgolly.scalajs.react.*

import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel

/**
 * client.jsの処理のexport
 * bbsClientで独立してリンクする
 */
object Main {

  @islands("site-header.tsx")
  @JSExportTopLevel("SiteHeaderJs", "client")
  val siteHeader: js.Any = SiteHeaderIsland.JsComponent.toJsComponent.raw

  @islands("board-details.tsx")
  @JSExportTopLevel("BoardDetailsJs", "client")
  val boardDetails: js.Any = BoardDetailsIsland.JsComponent.toJsComponent.raw

  @islands("posting-form.tsx")
  @JSExportTopLevel("PostingFormJs", "client")
  val postingForm: js.Any = PostingFormIsland.JsComponent.toJsComponent.raw

  @islands("thread-list.tsx")
  @JSExportTopLevel("ThreadListJs", "client")
  val threadList: js.Any = ThreadListIsland.JsComponent.toJsComponent.raw
  @islands("auth-form.tsx")
  @JSExportTopLevel("AuthFormJs", "client")
  final val authForm: js.Any = AuthFormIsland.Component.toJsComponent.raw

  @islands("auth-modal.tsx")
  @JSExportTopLevel("AuthModalJs", "client")
  final val authModal: js.Any = AuthModalIsland.Component.toJsComponent.raw

  @islands("account-nav.tsx")
  @JSExportTopLevel("AccountNavJs", "client")
  final val accountNav: js.Any = AccountNavIsland.Component.toJsComponent.raw

  @islands("account-menu.tsx")
  @JSExportTopLevel("AccountMenuJs", "client")
  final val accountMenu: js.Any = AccountMenuIsland.Component.toJsComponent.raw

  @islands("account-logout.tsx")
  @JSExportTopLevel("AccountLogoutJs", "client")
  final val accountLogout: js.Any = AccountLogoutIsland.Component.toJsComponent.raw
}
