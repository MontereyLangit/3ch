package io._3ch.bbs.external.honox

import japgolly.scalajs.react.facade.{HasMutableDisplayName, React}
import japgolly.scalajs.react.vdom.VdomElement
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

private final class IslandProps[P <: js.Object](
  val Component: React.ComponentType[P],
  val props: P,
  val componentName: String
) extends js.Object

@js.native
@JSImport("honox/vite/components", "HonoXIsland")
private object HonoXIsland extends js.Function1[IslandProps[?], React.Node] with HasMutableDisplayName {
  def apply(props: IslandProps[?]): React.Node = js.native
}

/** HonoX の hydration 境界を付ける。componentName は生成された client island の識別子 */
object Island {
  def apply[P <: js.Object](component: React.ComponentType[P], props: P, componentName: String): VdomElement = {
    VdomElement(React.createElement(HonoXIsland, new IslandProps[P](component, props, componentName)))
  }
}
