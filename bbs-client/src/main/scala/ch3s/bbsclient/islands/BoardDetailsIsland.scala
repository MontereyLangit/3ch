package ch3s.bbsclient.islands

import japgolly.scalajs.react.*
import japgolly.scalajs.react.vdom.html_<^.*

import scala.scalajs.js
import scala.scalajs.js.URIUtils

/** 板概要・板設定・新着レス一覧・新規スレッド作成の開閉ボタン・パネル */
object BoardDetailsIsland {

  final case class Props(bbs: String, headText: String, settingsText: String, posting: PostingFormIsland.Props)

  object Props {
    def fromJs(raw: js.Object): Props = {
      val o = raw.asInstanceOf[js.Dynamic]
      def text(name: String): String = o.selectDynamic(name).asInstanceOf[js.UndefOr[String]].getOrElse("")
      Props(text("bbs"), text("headText"), text("settingsText"), PostingFormIsland.Props.fromJs(raw))
    }
  }

  final case class State(expanded: Set[String])

  private val panelClass = "board-panel details-content mt-2 mb-3 ml-[2em] text-[13px] max-[660px]:ml-3"

  private val buttons = List(
    "board-rules" -> "板概要・ローカルルール",
    "board-settings" -> "板設定",
    "board-headline" -> "新着レス一覧",
    "board-new-thread" -> "新規スレッド作成"
  )

  final class Backend($: BackendScope[Props, State]) {
    private def toggle(id: String): Callback = $.modState(s => State(if s.expanded.contains(id) then s.expanded - id else s.expanded + id))

    private def button(id: String, label: String, expanded: Boolean) = {
      <.button(^.key := id, ^.`type` := "button", ^.className := "cursor-pointer border-0 bg-transparent px-1 py-[3px] text-xs hover:text-[#2464b5]",
        VdomAttr("data-board-panel") := id,
        VdomAttr("aria-expanded") := expanded.toString, VdomAttr("aria-controls") := id,
        ^.onClick --> toggle(id),
        <.span(^.aria.hidden := true, if expanded then "▾ " else "▸ "), label)
    }

    def render(props: Props, state: State): VdomNode = {
      def closed(id: String): Boolean = !state.expanded.contains(id)
      <.div(^.className := "contents",
        <.div(^.className := "board-details mt-[9px] flex flex-wrap items-center gap-2",
          buttons.toVdomArray { case (id, label) => button(id, label, state.expanded.contains(id)) }),
        <.div(^.id := "board-rules", ^.hidden := closed("board-rules"),
          ^.className := s"$panelClass [&_a]:text-[#06f] [&_a:hover]:underline", ^.dangerouslySetInnerHtml := props.headText),
        <.div(^.id := "board-settings", ^.className := panelClass, ^.hidden := closed("board-settings"),
          <.div(
            <.pre(^.className := "m-0 whitespace-pre-wrap font-mono text-xs", props.settingsText),
            <.a(^.className := "text-[#06f] hover:underline", ^.href := s"/${URIUtils.encodeURIComponent(props.bbs)}/SETTING.TXT", "SETTING.TXT"))),
        <.div(^.id := "board-headline", ^.className := panelClass, ^.hidden := closed("board-headline"),
          <.div(^.className := "side-headline", <.p(^.className := "coming-soon m-2 py-6 text-center text-[#697785]", "Coming Soon…"))),
        <.div(^.id := "board-new-thread", ^.className := "board-panel", ^.hidden := closed("board-new-thread"),
          PostingFormIsland.Component(props.posting))
      )
    }
  }

  final val Component = ScalaComponent
    .builder[Props]("BoardDetails")
    .initialState(State(Set.empty))
    .backend(new Backend(_))
    .renderPS((self, p, s) => self.backend.render(p, s))
    .build
}
