package io._3ch.bbs.components.layout

import io._3ch.bbs.generated.IslandComponents
import io._3ch.common.domain.account.SessionView
import japgolly.scalajs.react.ScalaFnComponent
import scala.scalajs.js

final case class HeaderProps(authEnabled: Boolean = false, session: Option[SessionView] = None, returnPath: String = "/")

/** 共通ヘッダーの表示情報をislandへ渡す */
object HeaderComponent {
  def islandProps(props: HeaderProps): js.Object = {
    val raw = js.Dynamic.literal(authEnabled = props.authEnabled, returnPath = props.returnPath)
    props.session.foreach(s => raw.updateDynamic("session")(js.Dynamic.literal(uid = s.uid, lv = s.lv)))
    raw
  }
  final val AuthComponent = ScalaFnComponent[HeaderProps] { props => IslandComponents.SiteHeaderJs(islandProps(props)) }
  final val Component = ScalaFnComponent[Unit] { _ => AuthComponent(HeaderProps()) }
}
