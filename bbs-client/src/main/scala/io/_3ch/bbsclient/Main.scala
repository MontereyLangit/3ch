package io._3ch.bbsclient

import hx4s.annotation.islands
import hx4s.react.IslandExport
import io._3ch.bbsclient.islands.*
import io._3ch.common.islands.{AccountCapProps, AccountCredentialsProps, AccountLogoutProps, AccountMenuProps, AccountNavProps, AuthFormProps,
  AuthTokenProps, BoardDetailsProps, PostingFormProps, SiteHeaderProps, ThreadListProps}

import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel

/**
 * client.jsの処理のexport
 * bbsClientで独立してリンクする。props は IslandExport が fields で検証してから描画する
 */
object Main {

  @islands[io._3ch.common.islands.SiteHeaderProps]("site-header.tsx")
  @JSExportTopLevel("SiteHeaderJs", "client")
  final val siteHeader: IslandExport[SiteHeaderProps] = IslandExport("SiteHeaderJs", SiteHeaderProps.fields)(SiteHeaderIsland.Component(_))

  @islands[io._3ch.common.islands.BoardDetailsProps]("board-details.tsx")
  @JSExportTopLevel("BoardDetailsJs", "client")
  final val boardDetails: IslandExport[BoardDetailsProps] = IslandExport("BoardDetailsJs", BoardDetailsProps.fields)(BoardDetailsIsland.render)

  @islands[io._3ch.common.islands.PostingFormProps]("posting-form.tsx")
  @JSExportTopLevel("PostingFormJs", "client")
  final val postingForm: IslandExport[PostingFormProps] = IslandExport("PostingFormJs", PostingFormProps.fields)(PostingFormIsland.render)

  @islands[io._3ch.common.islands.ThreadListProps]("thread-list.tsx")
  @JSExportTopLevel("ThreadListJs", "client")
  final val threadList: IslandExport[ThreadListProps] = IslandExport("ThreadListJs", ThreadListProps.fields)(ThreadListIsland.render)

  @islands[io._3ch.common.islands.AuthFormProps]("auth-form.tsx")
  @JSExportTopLevel("AuthFormJs", "client")
  final val authForm: IslandExport[AuthFormProps] = IslandExport("AuthFormJs", AuthFormProps.fields)(AuthFormIsland.Component(_))

  @islands("auth-modal.tsx")
  @JSExportTopLevel("AuthModalJs", "client")
  final val authModal: js.Any = AuthModalIsland.Component.toJsComponent.raw

  @islands[io._3ch.common.islands.AccountNavProps]("account-nav.tsx")
  @JSExportTopLevel("AccountNavJs", "client")
  final val accountNav: IslandExport[AccountNavProps] = IslandExport("AccountNavJs", AccountNavProps.fields)(AccountNavIsland.Component(_))

  @islands[io._3ch.common.islands.AccountMenuProps]("account-menu.tsx")
  @JSExportTopLevel("AccountMenuJs", "client")
  final val accountMenu: IslandExport[AccountMenuProps] = IslandExport("AccountMenuJs", AccountMenuProps.fields)(AccountMenuIsland.Component(_))

  @islands[io._3ch.common.islands.AccountCapProps]("account-cap.tsx")
  @JSExportTopLevel("AccountCapJs", "client")
  final val accountCap: IslandExport[AccountCapProps] = IslandExport("AccountCapJs", AccountCapProps.fields)(AccountCapIsland.Component(_))

  @islands[io._3ch.common.islands.AccountLogoutProps]("account-logout.tsx")
  @JSExportTopLevel("AccountLogoutJs", "client")
  final val accountLogout: IslandExport[AccountLogoutProps] = IslandExport("AccountLogoutJs", AccountLogoutProps.fields)(AccountLogoutIsland.Component(_))

  @islands[io._3ch.common.islands.AuthTokenProps]("auth-token.tsx")
  @JSExportTopLevel("AuthTokenJs", "client")
  final val authToken: IslandExport[AuthTokenProps] = IslandExport("AuthTokenJs", AuthTokenProps.fields)(AuthTokenIsland.Component(_))

  @islands[io._3ch.common.islands.AccountCredentialsProps]("account-credentials.tsx")
  @JSExportTopLevel("AccountCredentialsJs", "client")
  final val accountCredentials: IslandExport[AccountCredentialsProps] =
    IslandExport("AccountCredentialsJs", AccountCredentialsProps.fields)(AccountCredentialsIsland.Component(_))
}
