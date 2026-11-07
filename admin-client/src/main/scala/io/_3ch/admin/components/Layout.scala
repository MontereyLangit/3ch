package io._3ch.admin.components

import io._3ch.admin.{AdminPage, LoadState, Nav}
import io._3ch.common.admin.RuleName
import japgolly.scalajs.react.*
import japgolly.scalajs.react.vdom.html_<^.*

/** 共通レイアウト。上部のバー、左のメニュー列、本文、フッター */
object Layout {
  private val header =
    """sticky top-0 z-10 flex min-h-16 flex-wrap items-center gap-5 border-b-[1.5px] border-[#f66] bg-[#f9f9f9] px-[15px] py-2.5
      | shadow-[0_1px_4px_#0002] max-[767px]:gap-3 max-[767px]:px-2""".stripMargin.replace("\n", "")
  private val navLink = "block py-[3px] pl-2 text-[#333] no-underline hover:underline max-[767px]:p-0"
  private val navCurrent = "block border-l-2 border-[#f66] py-[3px] pl-1.5 font-bold text-[#333] no-underline max-[767px]:border-b-2 max-[767px]:border-l-0 max-[767px]:p-0"
  private val section = "min-h-[50px] border-b border-[#c8c8c8] px-2.5 py-2 max-[767px]:min-h-0"
  private val links = "max-[767px]:flex max-[767px]:flex-wrap max-[767px]:gap-x-2.5 max-[767px]:gap-y-0.5"

  private def item(nav: Nav, page: AdminPage, label: String): VdomElement = {
    val current = nav.page == page
    Link(nav, page, if (current) navCurrent else navLink, label)
  }

  private def boardSection(nav: Nav, boards: LoadState[List[String]]): VdomElement = {
    val current = nav.page.board
    val names = boards match {
      case LoadState.Ready(list) => (list ++ current).distinct.sorted
      case _ => current.toList
    }
    <.section(^.className := section, <.h2(^.className := "mb-1 font-bold", "■ 板"),
      <.select(^.className := s"${Styles.select} w-full", ^.aria.label := "管理する板", ^.value := current.getOrElse(""),
        ^.onChange ==> ((e: ReactEventFromInput) => Callback.when(AdminPage.validBoard(e.target.value))(nav.go(AdminPage.Board(e.target.value)))),
        <.option(^.value := "", "板を選ぶ"),
        names.toVdomArray(name => <.option(^.key := name, ^.value := name, name))),
      current.fold[VdomNode](<.p(^.className := s"${Styles.notice} mt-1.5", "板を選ぶと、板設定とスレ一覧を開けます。")) { bbs =>
        <.div(^.className := s"mt-1.5 $links", item(nav, AdminPage.Board(bbs), "板設定"), item(nav, AdminPage.Threads(bbs), "スレ一覧・スレ管理"))
      })
  }

  def apply(nav: Nav, boards: LoadState[List[String]], content: VdomNode*): VdomElement =
    <.div(^.className := "min-h-screen bg-[#fafafa] text-sm text-[#333]",
      <.header(^.className := header,
        <.a(^.href := "/", ^.className := "ml-[15px] shrink-0", ^.aria.label := "３ちゃんねる ホーム",
          <.img(^.src := "/static/3ch_logo.png", ^.alt := "３ちゃんねる", ^.className := "inline-block h-[30px] align-middle")),
        <.nav(^.className := "flex flex-wrap items-center gap-3.5", ^.aria.label := "管理画面",
          Link(nav, AdminPage.Top, "font-bold text-[#333] no-underline", "管理画面"), <.span("|"),
          <.a(^.href := "/", ^.className := "text-[#333] no-underline hover:underline", "掲示板へ戻る"))),
      <.div(^.className := "grid grid-cols-[200px_minmax(0,1fr)] items-start p-2 max-[767px]:block",
        <.aside(^.className := "min-w-0 self-stretch border-r border-[#bdbdbd] text-[0.9em] max-[767px]:mb-2 max-[767px]:border-r-0 max-[767px]:border-b",
          ^.aria.label := "管理メニュー",
          boardSection(nav, boards),
          <.section(^.className := section, <.h2(^.className := "mb-1 font-bold", "■ 全体"),
            <.div(^.className := links, item(nav, AdminPage.Rules(RuleName.Access), "規制ルール（アクセス）"),
              item(nav, AdminPage.Rules(RuleName.Ngwords), "規制ルール（NGワード）"), item(nav, AdminPage.Menu, "板一覧（bbsmenu）")))),
        <.main(^.className := "min-w-0 pl-3 max-[767px]:p-0", content.toTagMod)),
      <.footer(^.className := "px-2 py-3 text-center text-xs",
        <.a(^.href := "https://github.com/MontereyLangit/3ch", ^.className := Styles.link, "3ch"), " ",
        <.a(^.href := "https://github.com/MontereyLangit/", ^.className := "font-bold text-[rgb(254,100,46)] no-underline", "†Monterey Langit ★")))
}
