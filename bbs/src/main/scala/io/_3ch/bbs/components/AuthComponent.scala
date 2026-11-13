package io._3ch.bbs.components

import io._3ch.bbs.components.layout.{FooterComponent, HeaderComponent, HeaderProps}
import io._3ch.bbs.generated.IslandComponents
import japgolly.scalajs.react.ScalaFnComponent
import japgolly.scalajs.react.vdom.html_<^.*
import scala.scalajs.js

final case class AuthProps(turnstileSiteKey: String, returnPath: String = "/", embed: Boolean = false, login: Boolean = false, agreementVersion: String = "",
  googleEnabled: Boolean = false, notice: String = "")

/** 通常表示とモーダル内で共通の認証画面 */
object AuthComponent {
  final val Component = ScalaFnComponent[AuthProps] { props =>
    <.div(^.className := "min-h-screen bg-[#fafafa] text-sm text-[#333]",
      if props.embed then EmptyVdom else HeaderComponent.AuthComponent(HeaderProps(authEnabled = true, returnPath = props.returnPath)),
      <.main(^.className := "mx-auto max-w-3xl p-4",
        <.h1(^.className := "mb-3 text-lg font-bold", "認証・登録"),
        <.p(^.className := "mb-3", "書き込みには認証・登録が必要です。"),
        IslandComponents.AuthFormJs(js.Dynamic.literal(turnstileSiteKey = props.turnstileSiteKey, returnPath = props.returnPath,
          embed = props.embed, login = props.login, agreementVersion = props.agreementVersion, googleEnabled = props.googleEnabled, notice = props.notice))),
      if props.embed then EmptyVdom else FooterComponent.Component())
  }
}
