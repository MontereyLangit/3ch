package io._3ch.bbs.external.honox

import japgolly.scalajs.react.facade.{HasMutableDisplayName, React}
import japgolly.scalajs.react.vdom.VdomElement
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

private final class IslandProps(
  val Component: React.ComponentType[js.Object],
  val props: js.Object,
  val componentName: String
) extends js.Object

@js.native
@JSImport("honox/vite/components", "HonoXIsland")
private object HonoXIsland extends js.Function1[IslandProps, React.Node] with HasMutableDisplayName {
  def apply(props: IslandProps): React.Node = js.native
}

/** HonoX の hydration 境界を付ける。componentName は生成された client island の識別子。 */
object Island {
  def apply(component: React.ComponentType[js.Object], props: js.Object, componentName: String): VdomElement = {
    VdomElement(React.createElement(HonoXIsland, new IslandProps(component, props, componentName)))
  }
}
