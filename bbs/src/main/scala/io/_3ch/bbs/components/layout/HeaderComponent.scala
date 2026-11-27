package io._3ch.bbs.components.layout

import io._3ch.bbs.components.PostingFormComponent
import io._3ch.bbs.generated.IslandComponents
import io._3ch.common.domain.account.SessionView
import io._3ch.common.islands
import japgolly.scalajs.react.ScalaFnComponent
import scala.scalajs.js.JSConverters.*

final case class HeaderProps(authEnabled: Boolean = false, session: Option[SessionView] = None, returnPath: String = "/")

/** 共通ヘッダーの表示情報をislandへ渡す */
object HeaderComponent {
  def islandProps(props: HeaderProps): islands.SiteHeaderProps = new islands.SiteHeaderProps {
    val authEnabled = props.authEnabled
    val returnPath = props.returnPath
    val session = props.session.map(PostingFormComponent.sessionProps).orUndefined
  }
  final val AuthComponent = ScalaFnComponent[HeaderProps] { props => IslandComponents.SiteHeaderJs(islandProps(props)) }
  final val Component = ScalaFnComponent[Unit] { _ => AuthComponent(HeaderProps()) }
}
