package io._3ch.bbs.components

import io._3ch.bbs.components.layout.{FooterComponent, HeaderComponent, HeaderProps}
import io._3ch.bbs.generated.IslandComponents
import io._3ch.common.domain.account.{AccountKind, SessionView}
import io._3ch.common.domain.device.DeviceGranularity
import io._3ch.common.text.JstTime
import japgolly.scalajs.react.ScalaFnComponent
import japgolly.scalajs.react.vdom.html_<^.*
import scala.scalajs.js

final case class AccountPostProps(t: Double, bbs: String, key: String, title: String, resNum: Int, excerpt: String, network: String)
final case class AccountTokenWarningProps(t: Double, uaLabel: String)
final case class AccountTokenProps(id: String, label: String, issuedAt: Double = 0, expiresAt: Double = 0,
  boundAt: Option[Double] = None, lastUsedAt: Option[Double] = None, revokedAt: Option[Double] = None,
  revokeReason: Option[String] = None, replacedTokenAt: Option[Double] = None,
  recentPosts: Seq[AccountPostProps] = Nil, warnings: Seq[AccountTokenWarningProps] = Nil)
/** アカウント設定画面の表示情報。device はこの端末の Cookie にある端末ハッシュの粒度 */
final case class AccountProps(uid: String, lv: Int, tokens: Seq[AccountTokenProps] = Nil, activeCount: Int = 0, nowSeconds: Double = js.Date.now() / 1000,
  kind: AccountKind = AccountKind.Client, expiresAt: Option[Double] = None, device: Option[DeviceGranularity] = None, deviceCount: Int = 0)

/** アカウントの表示情報と専ブラ用キー・ログアウトの操作画面 */
object AccountComponent {
  private def tokenProps(token: AccountTokenProps): js.Object = {
    val raw = js.Dynamic.literal(id = token.id, label = token.label, issuedAt = token.issuedAt, expiresAt = token.expiresAt,
      recentPosts = js.Array(token.recentPosts.map(p => js.Dynamic.literal(t = p.t, bbs = p.bbs, key = p.key, title = p.title, resNum = p.resNum, excerpt = p.excerpt, network = p.network))*),
      warnings = js.Array(token.warnings.map(w => js.Dynamic.literal(t = w.t, uaLabel = w.uaLabel))*))
    Seq("boundAt" -> token.boundAt, "lastUsedAt" -> token.lastUsedAt, "revokedAt" -> token.revokedAt, "replacedTokenAt" -> token.replacedTokenAt).foreach { case (name, value) => value.foreach(v => raw.updateDynamic(name)(v)) }
    token.revokeReason.foreach(v => raw.updateDynamic("revokeReason")(v))
    raw
  }
  private def kindLabel(kind: AccountKind): String = kind match {
    case AccountKind.Client => "かんたん登録（クライアントID）"
    case AccountKind.Password => "ID・パスワード"
    case AccountKind.Google => "Google"
  }
  private def deviceLabel(device: Option[DeviceGranularity]): String = device match {
    case Some(DeviceGranularity.Personal) => "準個体（p）"
    case Some(DeviceGranularity.Class) => "機種クラス（g）"
    case Some(DeviceGranularity.Unknown) => "不明（u）"
    case None => "端末情報なし"
  }
  private val term = "font-bold text-[#697785]"
  final val Component = ScalaFnComponent[AccountProps] { props =>
    <.div(^.className := "min-h-screen bg-[#fafafa] text-sm text-[#333]",
      HeaderComponent.AuthComponent(HeaderProps(true, Some(SessionView(props.uid, props.lv)), "/auth/account")),
      <.main(^.className := "mx-auto max-w-3xl p-4", <.h1(^.className := "mb-3 text-lg font-bold", "アカウント設定"),
        <.section(^.className := "border border-[#d6dce3] bg-white p-4", <.h2(^.className := "mb-2 font-bold", "■ アカウント"),
          <.dl(^.className := "grid grid-cols-[7em_minmax(0,1fr)] gap-x-3 gap-y-2",
            <.dt(^.className := term, "ユーザーID"), <.dd(<.b(s"@${props.uid}"), <.small(^.className := "ml-2 text-[#697785]", "名前欄に!idと書くと表示されます")),
            <.dt(^.className := term, "登録方法"), <.dd(kindLabel(props.kind),
              props.expiresAt.filter(_ => props.kind == AccountKind.Client).fold(EmptyVdom)(at =>
                <.small(^.className := "ml-2 text-[#697785]", s"${JstTime.date(at)}まで有効（最後の書き込みから1年。書き込むたびに延びます）"))),
            <.dt(^.className := term, "Lv"), <.dd(if props.lv >= 10 then "Lv*" else s"Lv${props.lv}"),
            <.dt(^.className := term, "この端末"), <.dd(deviceLabel(props.device), <.small(^.className := "ml-2 text-[#697785]", s"登録した端末${props.deviceCount}台")))),
        <.section(^.id := "upgrade", ^.className := "my-4 border border-[#d6dce3] bg-white p-4", <.h2(^.className := "mb-2 font-bold", "■ 登録方法の切り替え"),
          <.p("ID・パスワード、Googleへの切り替えは準備中です。")),
        IslandComponents.AccountMenuJs(js.Dynamic.literal(tokens = js.Array(props.tokens.map(tokenProps)*), activeCount = props.activeCount, nowSeconds = props.nowSeconds)),
        IslandComponents.AccountLogoutJs(js.Dynamic.literal(simple = props.kind == AccountKind.Client))),
      FooterComponent.Component())
  }
}
