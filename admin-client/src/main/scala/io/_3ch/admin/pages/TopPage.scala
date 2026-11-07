package io._3ch.admin.pages

import io._3ch.admin.components.{Link, Styles}
import io._3ch.admin.{AdminPage, LoadState, Nav}
import io._3ch.common.admin.RuleName
import japgolly.scalajs.react.*
import japgolly.scalajs.react.vdom.html_<^.*

/** トップ。板の一覧と板名の入力、全体の設定へのリンク */
object TopPage {
  final case class Props(nav: Nav, boards: LoadState[List[String]])

  final val Component = ScalaFnComponent[Props] { props =>
    for {
      name <- useState("")
    } yield {
      val nav = props.nav
      val valid = AdminPage.validBoard(name.value.trim)
      def open(page: String => AdminPage): Callback = Callback.when(valid)(nav.go(page(name.value.trim)))
      <.div(
        <.h1(^.className := Styles.h1, "管理画面"),
        <.div(^.className := Styles.panel,
          <.h2(^.className := Styles.h2, "■ 板を開く"),
          <.form(^.className := "flex flex-wrap items-center gap-2", ^.onSubmit ==> (e => e.preventDefaultCB >> open(AdminPage.Board(_))),
            <.label(^.htmlFor := "board-name", "板名"),
            <.input(^.id := "board-name", ^.`type` := "text", ^.className := s"${Styles.input} w-48", ^.value := name.value, ^.placeholder := "例: sandbox",
              ^.onChange ==> ((e: ReactEventFromInput) => name.setState(e.target.value))),
            <.button(^.`type` := "submit", ^.className := Styles.button, ^.disabled := !valid, "板設定"),
            <.button(^.`type` := "button", ^.className := Styles.button, ^.disabled := !valid, ^.onClick --> open(AdminPage.Threads(_)), "スレ一覧")),
          <.p(^.className := s"${Styles.notice} mt-1.5", "板名は英数字・_・-で入力します。一覧に無い板も開けます。")),
        <.div(^.className := Styles.panel,
          <.h2(^.className := Styles.h2, "■ 板の一覧"),
          props.boards match {
            case LoadState.Loading => <.p(^.className := Styles.notice, "読み込み中…")
            case LoadState.Failed(error) => <.p(^.className := Styles.error, error.message)
            case LoadState.Ready(Nil) => <.p(^.className := Styles.notice, "板が見つかりません。板名を入力して開いてください。")
            case LoadState.Ready(boards) => <.table(^.className := "w-full border-collapse text-[13px]",
              <.thead(<.tr(<.th(^.className := "border-b border-[#dcdcdc] p-2 text-left text-xs font-normal text-[#465565]", "板名"),
                <.th(^.className := "border-b border-[#dcdcdc] p-2 text-left text-xs font-normal text-[#465565]", "操作"))),
              <.tbody(boards.toVdomArray { bbs =>
                <.tr(^.key := bbs, ^.className := "hover:bg-[#fafdff]",
                  <.td(^.className := "border-b border-[#dce1e6] p-2", <.a(^.href := s"/$bbs/", ^.className := Styles.link, bbs)),
                  <.td(^.className := "border-b border-[#dce1e6] p-2",
                    Link(nav, AdminPage.Board(bbs), Styles.link, "板設定"), " ・ ", Link(nav, AdminPage.Threads(bbs), Styles.link, "スレ一覧")))
              }))
          }),
        <.div(^.className := Styles.panel,
          <.h2(^.className := Styles.h2, "■ 全体の設定"),
          <.ul(^.className := "list-none p-0",
            <.li(Link(nav, AdminPage.Rules(RuleName.Access), Styles.link, "規制ルール（アクセス）"), <.span(^.className := s"${Styles.notice} ml-2", "deny.cgi")),
            <.li(Link(nav, AdminPage.Rules(RuleName.Ngwords), Styles.link, "規制ルール（NGワード）"), <.span(^.className := s"${Styles.notice} ml-2", "ngwords.cgi")),
            <.li(Link(nav, AdminPage.Menu, Styles.link, "板一覧（bbsmenu）"), <.span(^.className := s"${Styles.notice} ml-2", "bbsmenu.html")))))
    }
  }
}
