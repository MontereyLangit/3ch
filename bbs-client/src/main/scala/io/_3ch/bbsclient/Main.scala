package io._3ch.bbsclient

import io._3ch.bbsclient.islands.*
import io._3ch.common.routes.islands

import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel

/**
 * client.jsの処理のexport
 * bbsClientで独立してリンクする
 */
object Main {

  @islands[io._3ch.common.islands.SiteHeaderProps]("site-header.tsx")
  @JSExportTopLevel("SiteHeaderJs", "client")
  val siteHeader: js.Any = SiteHeaderIsland.JsComponent.toJsComponent.raw

  @islands[io._3ch.common.islands.BoardDetailsProps]("board-details.tsx")
  @JSExportTopLevel("BoardDetailsJs", "client")
  val boardDetails: js.Any = BoardDetailsIsland.JsComponent.toJsComponent.raw

  @islands[io._3ch.common.islands.PostingFormProps]("posting-form.tsx")
  @JSExportTopLevel("PostingFormJs", "client")
  val postingForm: js.Any = PostingFormIsland.JsComponent.toJsComponent.raw

  @islands[io._3ch.common.islands.ThreadListProps]("thread-list.tsx")
  @JSExportTopLevel("ThreadListJs", "client")
  val threadList: js.Any = ThreadListIsland.JsComponent.toJsComponent.raw

  @islands[io._3ch.common.islands.AuthFormProps]("auth-form.tsx")
  @JSExportTopLevel("AuthFormJs", "client")
  final val authForm: js.Any = AuthFormIsland.Component.toJsComponent.raw

  @islands("auth-modal.tsx")
  @JSExportTopLevel("AuthModalJs", "client")
  final val authModal: js.Any = AuthModalIsland.Component.toJsComponent.raw

  @islands[io._3ch.common.islands.AccountNavProps]("account-nav.tsx")
  @JSExportTopLevel("AccountNavJs", "client")
  final val accountNav: js.Any = AccountNavIsland.Component.toJsComponent.raw

  @islands[io._3ch.common.islands.AccountMenuProps]("account-menu.tsx")
  @JSExportTopLevel("AccountMenuJs", "client")
  final val accountMenu: js.Any = AccountMenuIsland.Component.toJsComponent.raw

  @islands[io._3ch.common.islands.AccountCapProps]("account-cap.tsx")
  @JSExportTopLevel("AccountCapJs", "client")
  final val accountCap: js.Any = AccountCapIsland.Component.toJsComponent.raw

  @islands[io._3ch.common.islands.AccountLogoutProps]("account-logout.tsx")
  @JSExportTopLevel("AccountLogoutJs", "client")
  final val accountLogout: js.Any = AccountLogoutIsland.Component.toJsComponent.raw

  @islands[io._3ch.common.islands.AuthTokenProps]("auth-token.tsx")
  @JSExportTopLevel("AuthTokenJs", "client")
  final val authToken: js.Any = AuthTokenIsland.Component.toJsComponent.raw

  @islands[io._3ch.common.islands.AccountCredentialsProps]("account-credentials.tsx")
  @JSExportTopLevel("AccountCredentialsJs", "client")
  final val accountCredentials: js.Any = AccountCredentialsIsland.Component.toJsComponent.raw
}
