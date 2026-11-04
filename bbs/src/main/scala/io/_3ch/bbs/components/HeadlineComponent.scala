package io._3ch.bbs.components

import japgolly.scalajs.react.vdom.html_<^.*

/** 新着レス一覧 */
object HeadlineComponent {
  def content(bbs: String): VdomElement = <.div(^.className := "side-headline", ComingSoon.notice)
}
