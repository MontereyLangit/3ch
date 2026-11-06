package io._3ch.bbs.components

import io._3ch.bbs.components.layout.{FooterComponent, HeaderComponent, HeaderProps}
import io._3ch.bbs.generated.IslandComponents
import io._3ch.common.domain.account.SessionView
import japgolly.scalajs.react.ScalaFnComponent
import japgolly.scalajs.react.vdom.html_<^.*
import scala.scalajs.js

final case class AccountPostProps(t: Double, bbs: String, key: String, title: String, resNum: Int, excerpt: String, network: String)
final case class AccountTokenWarningProps(t: Double, uaLabel: String)
final case class AccountTokenProps(id: String, label: String, issuedAt: Double = 0, expiresAt: Double = 0,
  boundAt: Option[Double] = None, lastUsedAt: Option[Double] = None, revokedAt: Option[Double] = None,
  revokeReason: Option[String] = None, replacedTokenAt: Option[Double] = None,
  recentPosts: Seq[AccountPostProps] = Nil, warnings: Seq[AccountTokenWarningProps] = Nil)
final case class AccountProps(uid: String, lv: Int, tokens: Seq[AccountTokenProps] = Nil, activeCount: Int = 0, nowSeconds: Double = js.Date.now() / 1000)

/** アカウントの表示情報と専ブラ用キーの操作画面 */
object AccountComponent {
  private def tokenProps(token: AccountTokenProps): js.Object = {
    val raw = js.Dynamic.literal(id = token.id, label = token.label, issuedAt = token.issuedAt, expiresAt = token.expiresAt,
      recentPosts = js.Array(token.recentPosts.map(p => js.Dynamic.literal(t = p.t, bbs = p.bbs, key = p.key, title = p.title, resNum = p.resNum, excerpt = p.excerpt, network = p.network))*),
      warnings = js.Array(token.warnings.map(w => js.Dynamic.literal(t = w.t, uaLabel = w.uaLabel))*))
    Seq("boundAt" -> token.boundAt, "lastUsedAt" -> token.lastUsedAt, "revokedAt" -> token.revokedAt, "replacedTokenAt" -> token.replacedTokenAt).foreach { case (name, value) => value.foreach(v => raw.updateDynamic(name)(v)) }
    token.revokeReason.foreach(v => raw.updateDynamic("revokeReason")(v))
    raw
  }
  final val Component = ScalaFnComponent[AccountProps] { props =>
    <.div(^.className := "min-h-screen bg-[#fafafa] text-sm text-[#333]",
      HeaderComponent.AuthComponent(HeaderProps(true, Some(SessionView(props.uid, props.lv)), "/auth/account")),
      <.main(^.className := "mx-auto max-w-3xl p-4", <.h1(^.className := "mb-3 text-lg font-bold", "アカウント設定"),
        <.section(^.className := "border border-[#d6dce3] bg-white p-4", <.h2(^.className := "mb-2 font-bold", "■ アカウント"),
          <.p(<.b(s"@${props.uid}")), <.p(if props.lv >= 10 then "Lv*" else s"Lv${props.lv}")),
        <.section(^.id := "upgrade", ^.className := "my-4 border border-[#d6dce3] bg-white p-4", <.h2(^.className := "mb-2 font-bold", "■ 登録方法の切り替え"),
          <.p("ID・パスワード、Googleへの切り替えは準備中です。")),
        IslandComponents.AccountMenuJs(js.Dynamic.literal(tokens = js.Array(props.tokens.map(tokenProps)*), activeCount = props.activeCount, nowSeconds = props.nowSeconds))),
      FooterComponent.Component())
  }
}
