package io._3ch.bbs.components.layout

import io._3ch.bbs.generated.IslandComponents
import japgolly.scalajs.react.ScalaFnComponent

import scala.scalajs.js

/** 共通ヘッダー。表示と動作は bbs-client の island（SiteHeaderJs）が持つ。 */
object HeaderComponent {
  final val Component = ScalaFnComponent[Unit] { _ =>
    IslandComponents.SiteHeaderJs(js.Dynamic.literal().asInstanceOf[js.Object])
  }
}
