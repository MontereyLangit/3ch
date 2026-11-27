package io._3ch.bbs.components

import io._3ch.bbs.components.layout.{FooterComponent, HeaderComponent, HeaderProps}
import io._3ch.bbs.generated.IslandComponents
import io._3ch.common.islands
import japgolly.scalajs.react.ScalaFnComponent
import japgolly.scalajs.react.vdom.html_<^.*

/** メールのリンクの画面。reset ならパスワードの再設定、そうでなければメールアドレスの確認。enabled はメールの送信先が設定されているか。uid と email（伏せ字）は、確認の前に示す対象 */
final case class AuthTokenProps(reset: Boolean, token: String, enabled: Boolean, uid: String = "", email: String = "")

/** メールのリンクから開く確認・再設定の画面 */
object AuthTokenComponent {
  final val Component = ScalaFnComponent[AuthTokenProps] { props =>
    <.div(^.className := "min-h-screen bg-[#fafafa] text-sm text-[#333]",
      HeaderComponent.AuthComponent(HeaderProps(authEnabled = true)),
      <.main(^.className := "mx-auto max-w-3xl p-4",
        <.h1(^.className := "mb-3 text-lg font-bold", if props.reset then "パスワードの再設定" else "メールアドレスの確認"),
        IslandComponents.AuthTokenJs(new islands.AuthTokenProps {
          val mode = if props.reset then "reset" else "email"
          val token = props.token
          val enabled = props.enabled
          val uid = props.uid
          val email = props.email
        })),
      FooterComponent.Component())
  }
}
