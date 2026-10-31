package ch3s.bbs.components

import japgolly.scalajs.react.vdom.html_<^.*

object HeadlineComponent {
  def content(bbs: String): VdomElement = {
    <.div(^.className := "side-headline max-h-[300px] overflow-y-auto",
      <.small(^.className := "dummy-notice m-2 text-xs text-[#697785]", "ダミーデータ"),
      DummyThreadFeed.threads.toVdomArray { thread =>
        <.div(^.key := thread.key,
          <.div(^.className := "headline-heading mt-[5px] bg-[#9696961a]", <.small(thread.latest.date), <.br, s"${thread.title} (${thread.count})"),
          <.div(thread.latest.message))
      }
    )
  }
}
