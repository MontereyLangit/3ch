package io._3ch.bbs.components

import io._3ch.bbs.components.layout.{FooterComponent, HeaderComponent, HeaderProps}
import io._3ch.bbs.generated.IslandComponents
import io._3ch.common.islands
import japgolly.scalajs.react.ScalaFnComponent
import japgolly.scalajs.react.vdom.html_<^.*

final case class AuthProps(
    turnstileSiteKey: String,
    returnPath: String = "/",
    embed: Boolean = false,
    login: Boolean = false,
    agreementVersion: String = "",
    googleEnabled: Boolean = false,
    notice: String = "",
    mailEnabled: Boolean = false,
    upgrade: Boolean = false
)

/** 通常表示とモーダル内で共通の認証画面 */
object AuthComponent {
  final val Component = ScalaFnComponent[AuthProps] { props =>
    <.div(
      ^.className := "min-h-screen bg-[#fafafa] text-sm text-[#333]",
      if props.embed then EmptyVdom else HeaderComponent.AuthComponent(HeaderProps(authEnabled = true, returnPath = props.returnPath)),
      <.main(
        ^.className := "mx-auto max-w-3xl p-4",
        <.h1(^.className := "mb-3 text-lg font-bold", if props.upgrade then "登録方法の切り替え" else "認証・登録"),
        if props.upgrade then <.p(^.className := "mb-3", "かんたん登録のアカウントをID・パスワードかGoogleに切り替えます。Lvと書き込み履歴は引き継ぎ、ユーザーIDは登録したIDに変わります。元には戻せません。")
        else <.p(^.className := "mb-3", "書き込みには認証・登録が必要です。"),
        IslandComponents.AuthFormJs(new islands.AuthFormProps {
          val turnstileSiteKey = props.turnstileSiteKey
          val returnPath = props.returnPath
          val embed = props.embed
          val login = props.login
          val agreementVersion = props.agreementVersion
          val googleEnabled = props.googleEnabled
          val notice = props.notice
          val mailEnabled = props.mailEnabled
          val upgrade = props.upgrade
        })
      ),
      if props.embed then EmptyVdom else FooterComponent.Component()
    )
  }
}
