package ch3s.bbs.components

import japgolly.scalajs.react.ScalaFnComponent
import japgolly.scalajs.react.vdom.html_<^.*

case class ThreadFeedProps(bbs: String, time: String, query: String = "", sort: String = "new")

/** スレッドフィード */
object ThreadFeedComponent {
  final val Component = ScalaFnComponent[ThreadFeedProps] { _ =>
    <.div(^.className := "thread-feed", ComingSoon.notice)
  }
}

object ComingSoon {
  def notice: VdomElement = <.p(^.className := "coming-soon m-2 py-6 text-center text-[#697785]", "Coming Soon…")
}
