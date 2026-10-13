package ch3s.bbs.handler

import ch3s.bbs.external.honox.Island
import japgolly.scalajs.react.facade.React
import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel

/** Vite 上で実際の Scala bridge と HonoX の SSR / hydration を検証する入口。 */
object IslandTestExports {
  @JSExportTopLevel("renderIslandTest", "island-test")
  def renderIslandTest(component: React.ComponentType[js.Object], props: js.Object, componentName: String): React.Element = {
    Island(component, props, componentName).rawElement
  }
}
