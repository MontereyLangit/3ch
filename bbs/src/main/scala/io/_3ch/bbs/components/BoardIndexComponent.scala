package io._3ch.bbs.components

import io._3ch.bbs.components.layout.{FooterComponent, HeaderComponent, RightSidebarComponent, RightSidebarProps}
import io._3ch.bbs.generated.IslandComponents
import io._3ch.common.domain.{BbsSubjects, BoardId}
import japgolly.scalajs.react.ScalaFnComponent
import japgolly.scalajs.react.vdom.html_<^.*

import scala.scalajs.js
import scala.scalajs.js.URIUtils

case class BoardIndexProps(
  title: String,
  headText: String,
  bbs: BoardId,
  `type`: String = "subback",
  subjects: BbsSubjects = BbsSubjects(Nil),
  settings: Map[String, String] = Map.empty,
  query: String = "",
  sort: String = "new",
  time: String = "0",
  imgurClientId: String = "",
  now: Double = js.Date.now()
)

/** 板top */
object BoardIndexComponent {
  final val Component = ScalaFnComponent[BoardIndexProps] { props =>
    def tab(kind: String, label: String) = {
      val active = props.`type` == kind
      val href = s"?type=$kind&q=${URIUtils.encodeURIComponent(props.query)}&sort=${props.sort}"
      val tabClass =
        """tab mr-2.5 border-b-2 py-1 text-[#333] no-underline hover:border-[#f66] hover:no-underline max-[660px]:px-[9px]
          | max-[660px]:py-[7px] max-[660px]:text-xs """.stripMargin
      <.a(^.href := href, ^.className := s"$tabClass${if active then "active border-[#f66] font-bold" else "border-transparent"}",
        VdomAttr("aria-current") := (if active then "page" else "false"), label)
    }

    val details = PostingFormComponent.islandProps(PostingFormProps(props.bbs, props.time, imgurClientId = props.imgurClientId)).asInstanceOf[js.Dynamic]
    details.updateDynamic("headText")(props.headText)
    details.updateDynamic("settingsText")(props.settings.map { case (k, v) => s"$k=$v" }.mkString("\n"))

    val list = js.Dynamic.literal(bbs = props.bbs.value, `type` = props.`type`, subjectText = if props.`type` == "subback" then props.subjects.toString else "",
      query = props.query, sort = props.sort, now = props.now)

    <.div(^.className := "board-ui min-h-screen bg-[#fafafa] text-sm text-[#333] wrap-anywhere [--header-height:64px] [&_summary]:cursor-pointer",
      HeaderComponent.Component(),
      <.div(^.className := "board-layout grid p-2 grid-cols-[minmax(0,3fr)_minmax(0,1fr)] items-start max-[767px]:block",
        <.main(^.className := "board-main min-w-0 pr-2 max-[767px]:pr-0",
          <.h1(^.className := "board-title my-2 text-lg font-bold", props.title),
          IslandComponents.BoardDetailsJs(details.asInstanceOf[js.Object]),
          <.noscript(^.dangerouslySetInnerHtml := "<style>.board-ui .board-panel[hidden]{display:block!important}.board-ui .board-details,.board-ui [data-board-reload]{display:none}</style>"),
          <.div(^.className := "toolbar flex flex-wrap gap-2 p-[13px] max-[660px]:p-[9px]",
            <.nav(^.className := "tabs flex min-w-0 flex-wrap gap-[3px]", ^.aria.label := "表示切り替え",
              tab("feed", "スレッドフィード"), tab("subback", "スレ一覧"),
              <.span(^.className := "tab unavailable mr-2.5 cursor-default py-1 text-[#777] max-[660px]:px-[9px] max-[660px]:py-[7px] max-[660px]:text-xs", "おすすめ3ちゃんねる")),
            <.form(^.className := "right-controls ml-auto flex flex-wrap items-center gap-[7px] max-[660px]:ml-0 max-[660px]:w-full", ^.method := "get",
              <.input(^.`type` := "hidden", ^.name := "type", ^.value := props.`type`),
              <.input(^.id := "board-search",
                ^.className := "w-[220px] min-w-0 max-w-full scroll-mt-[calc(var(--header-height)+8px)] border border-[#d6dce3] bg-white px-2.5 py-1 text-base hover:border-[#b9c4d0] max-[660px]:w-full max-[660px]:flex-1",
                ^.`type` := "search", ^.name := "q", ^.defaultValue := props.query, ^.placeholder := "スレッドを検索", ^.aria.label := "スレッドを検索"),
              <.select(^.className := "cursor-pointer rounded-md border border-[#d6dce3] bg-white p-[7px] text-[11px] text-[#465565] hover:border-[#b9c4d0]",
                ^.name := "sort", ^.defaultValue := props.sort, ^.aria.label := "並び順",
                <.option(^.value := "new", "新着順"), <.option(^.value := "speed", "勢い順"), <.option(^.value := "res", "レス数順")),
              <.button(^.className := "cursor-pointer border border-[#d6dce3] bg-white px-2.5 py-1", ^.`type` := "submit", "表示")
            )
          ),
          IslandComponents.ThreadListJs(list.asInstanceOf[js.Object]),
          <.div(^.className := "board-admin p-3 text-right",
            <.a(^.className := "text-[#06f] hover:underline", ^.href := s"/admin/board?bbs=${URIUtils.encodeURIComponent(props.bbs.value)}", "管理画面へ"))
        ),
        RightSidebarComponent.Component(RightSidebarProps(props.bbs.value, props.headText, props.settings))
      ),
      FooterComponent.Component()
    )
  }
}
