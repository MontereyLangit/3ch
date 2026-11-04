package ch3s.bbs.handler

import ch3s.bbs.components.{BoardIndexComponent, BoardIndexProps}
import ch3s.bbs.external.honox.Island
import ch3s.common.domain.{BbsSubjects, BoardId}
import japgolly.scalajs.react.ReactDOMServer
import japgolly.scalajs.react.facade.React
import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel

/** Vite 上で実際の Scala bridge と HonoX の SSR / hydration を検証する入口 */
object IslandTestExports {
  @JSExportTopLevel("renderIslandTest", "island-test")
  def renderIslandTest(component: React.ComponentType[js.Object], props: js.Object, componentName: String): React.Element = {
    Island(component, props, componentName).rawElement
  }

  /**
   * 実際の板topのサーバー描画 HTML を返す
   * raw -> title,headText,bbs,type,subjectText,time,imgurClientId,now,settings
   */
  @JSExportTopLevel("renderBoardTopTest", "island-test")
  def renderBoardTopTest(raw: js.Object): String = {
    val o = raw.asInstanceOf[js.Dynamic]
    def text(name: String): String = o.selectDynamic(name).asInstanceOf[js.UndefOr[String]].getOrElse("")
    val settings = o.selectDynamic("settings").asInstanceOf[js.UndefOr[js.Dictionary[String]]].toOption.map(_.toMap).getOrElse(Map.empty)
    val props = BoardIndexProps(text("title"), text("headText"), BoardId.create(text("bbs")).fold(message => throw new IllegalArgumentException(message), identity), text("type"), BbsSubjects.create(text("subjectText")), settings,
      text("query"), text("sort"), text("time"), text("imgurClientId"), o.selectDynamic("now").asInstanceOf[Double])
    ReactDOMServer.renderToString(BoardIndexComponent.Component(props))
  }
}
