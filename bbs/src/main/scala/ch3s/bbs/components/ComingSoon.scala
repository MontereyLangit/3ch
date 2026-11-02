package ch3s.bbs.components

import japgolly.scalajs.react.vdom.html_<^.*

object ComingSoon {
  def notice: VdomElement = <.p(^.className := "coming-soon m-2 py-6 text-center text-[#697785]", "Coming Soon…")
}
