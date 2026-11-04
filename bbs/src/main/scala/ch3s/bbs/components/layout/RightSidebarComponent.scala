package ch3s.bbs.components.layout

import ch3s.bbs.components.{BoardSettingsComponent, HeadlineComponent}
import japgolly.scalajs.react.ScalaFnComponent
import japgolly.scalajs.react.vdom.html_<^.*

case class RightSidebarProps(bbs: String, headText: String, settings: Map[String, String])

/** 共通右サイドバー。PCは右端、モバイル等は本文の下に表示 */
object RightSidebarComponent {
  final val Component = ScalaFnComponent[RightSidebarProps] { props =>
    <.aside(^.className :=
      """side-panel min-w-0 self-stretch border-l border-[#bdbdbd] text-[.85em] [&_section]:min-h-[50px] [&_section]:border-b 
        | [&_section]:border-[#c8c8c8] [&_section]:px-2.5 [&_section]:py-2 [&_h2]:mb-1 [&_h2]:text-[1em] [&_h2]:font-bold max-[767px]:mt-[15px] 
        | max-[767px]:border-t max-[767px]:border-l-0""".stripMargin,
      ^.aria.label := "板の情報",
      <.div(^.className :=
        """side-inner sticky top-[var(--header-height)] max-h-[calc(100dvh-var(--header-height))] overflow-y-auto 
          | [scrollbar-width:thin] transition-[top] duration-200 max-[767px]:static max-[767px]:max-h-none""".stripMargin,
        <.section(<.h2("■ 板概要・ローカルルール"),
          <.div(^.className := "[&_a]:text-[#06f] [&_a:hover]:underline", ^.dangerouslySetInnerHtml := props.headText)),
        <.section(<.h2("■ 板設定"), BoardSettingsComponent.content(props.bbs, props.settings)),
        <.section(<.h2("■ 新着レス一覧"), HeadlineComponent.content(props.bbs))
      )
    )
  }
}
