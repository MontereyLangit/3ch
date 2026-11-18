package io._3ch.bbsclient

import hx4s.annotation.islands
import hx4s.react.IslandExport
import io._3ch.bbsclient.islands.*
import io._3ch.common.islands.{AccountCapProps, AccountCredentialsProps, AccountLogoutProps, AccountMenuProps, AccountNavProps, AuthFormProps,
  AuthTokenProps, BoardDetailsProps, PostingFormProps, SiteHeaderProps, ThreadListProps}

import scala.scalajs.js.annotation.JSExportTopLevel

object SiteHeaderEntry {
  @islands[io._3ch.common.islands.SiteHeaderProps]("site-header.tsx")
  @JSExportTopLevel("SiteHeaderJs", "island-site-header")
  def siteHeader(): IslandExport[SiteHeaderProps] = IslandExport("SiteHeaderJs", SiteHeaderProps.fields)(SiteHeaderIsland.Component(_))
}

object BoardDetailsEntry {
  @islands[io._3ch.common.islands.BoardDetailsProps]("board-details.tsx")
  @JSExportTopLevel("BoardDetailsJs", "island-board-details")
  def boardDetails(): IslandExport[BoardDetailsProps] = IslandExport("BoardDetailsJs", BoardDetailsProps.fields)(BoardDetailsIsland.render)
}

object PostingFormEntry {
  @islands[io._3ch.common.islands.PostingFormProps]("posting-form.tsx")
  @JSExportTopLevel("PostingFormJs", "island-posting-form")
  def postingForm(): IslandExport[PostingFormProps] = IslandExport("PostingFormJs", PostingFormProps.fields)(PostingFormIsland.render)
}

object ThreadListEntry {
  @islands[io._3ch.common.islands.ThreadListProps]("thread-list.tsx")
  @JSExportTopLevel("ThreadListJs", "island-thread-list")
  def threadList(): IslandExport[ThreadListProps] = IslandExport("ThreadListJs", ThreadListProps.fields)(ThreadListIsland.render)
}

object AuthFormEntry {
  @islands[io._3ch.common.islands.AuthFormProps]("auth-form.tsx")
  @JSExportTopLevel("AuthFormJs", "island-auth-form")
  def authForm(): IslandExport[AuthFormProps] = IslandExport("AuthFormJs", AuthFormProps.fields)(AuthFormIsland.Component(_))
}

object AuthModalEntry {
  @islands[scala.scalajs.js.Object]("auth-modal.tsx")
  @JSExportTopLevel("AuthModalJs", "island-auth-modal")
  def authModal(): IslandExport[scala.scalajs.js.Object] = AuthModalIsland.Component.toJsComponent.raw.asInstanceOf[IslandExport[scala.scalajs.js.Object]]
}

object AccountNavEntry {
  @islands[io._3ch.common.islands.AccountNavProps]("account-nav.tsx")
  @JSExportTopLevel("AccountNavJs", "island-account-nav")
  def accountNav(): IslandExport[AccountNavProps] = IslandExport("AccountNavJs", AccountNavProps.fields)(AccountNavIsland.Component(_))
}

object AccountMenuEntry {
  @islands[io._3ch.common.islands.AccountMenuProps]("account-menu.tsx")
  @JSExportTopLevel("AccountMenuJs", "island-account-menu")
  def accountMenu(): IslandExport[AccountMenuProps] = IslandExport("AccountMenuJs", AccountMenuProps.fields)(AccountMenuIsland.Component(_))
}

object AccountCapEntry {
  @islands[io._3ch.common.islands.AccountCapProps]("account-cap.tsx")
  @JSExportTopLevel("AccountCapJs", "island-account-cap")
  def accountCap(): IslandExport[AccountCapProps] = IslandExport("AccountCapJs", AccountCapProps.fields)(AccountCapIsland.Component(_))
}

object AccountLogoutEntry {
  @islands[io._3ch.common.islands.AccountLogoutProps]("account-logout.tsx")
  @JSExportTopLevel("AccountLogoutJs", "island-account-logout")
  def accountLogout(): IslandExport[AccountLogoutProps] = IslandExport("AccountLogoutJs", AccountLogoutProps.fields)(AccountLogoutIsland.Component(_))
}

object AuthTokenEntry {
  @islands[io._3ch.common.islands.AuthTokenProps]("auth-token.tsx")
  @JSExportTopLevel("AuthTokenJs", "island-auth-token")
  def authToken(): IslandExport[AuthTokenProps] = IslandExport("AuthTokenJs", AuthTokenProps.fields)(AuthTokenIsland.Component(_))
}

object AccountCredentialsEntry {
  @islands[io._3ch.common.islands.AccountCredentialsProps]("account-credentials.tsx")
  @JSExportTopLevel("AccountCredentialsJs", "island-account-credentials")
  def accountCredentials(): IslandExport[AccountCredentialsProps] =
    IslandExport("AccountCredentialsJs", AccountCredentialsProps.fields)(AccountCredentialsIsland.Component(_))
}
