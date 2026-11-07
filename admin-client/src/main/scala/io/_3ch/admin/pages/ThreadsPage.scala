package io._3ch.admin.pages

import io._3ch.admin.api.AdminApiClient
import io._3ch.admin.components.{Link, Styles}
import io._3ch.admin.{AdminPage, LoadState, Loaded, Nav}
import io._3ch.common.admin.AdminCodec
import io._3ch.common.domain.SubjectText
import japgolly.scalajs.react.*
import japgolly.scalajs.react.vdom.html_<^.*

/** 板のスレ一覧。公開中、削除・移動済み、key の直接指定 */
object ThreadsPage {
  final case class Props(nav: Nav, bbs: String)

  /** subject.txt の行のタイトルとレス数 */
  def title(line: String): String = SubjectText.decode(line.split("<>", 2).lift(1).getOrElse(line))

  final val Component = ScalaFnComponent[Props] { props =>
    for {
      loaded <- Loaded.useLoad(props.bbs)(() => AdminApiClient.get(s"/boards/${props.bbs}/threads/")(AdminCodec.threadList))
      key <- useState("")
    } yield {
      val nav = props.nav
      val bbs = props.bbs
      val validKey = AdminPage.validKey(key.value.trim)
      val cell = "border-b border-[#dce1e6] p-2 align-top"
      <.div(
        <.h1(^.className := Styles.h1, "スレ一覧 ", <.small(^.className := Styles.small, bbs)),
        <.nav(^.className := "flex flex-wrap gap-[3px] pt-1 pb-3",
          Link(nav, AdminPage.Board(bbs), "mr-2.5 border-b-2 border-transparent py-1 text-[#333] no-underline hover:border-[#f66]", "板設定"),
          <.span(^.className := "mr-2.5 border-b-2 border-[#f66] py-1 font-bold", "スレ一覧")),
        <.div(^.className := Styles.panel,
          <.form(^.className := "flex flex-wrap items-center gap-2", ^.onSubmit ==> (e => e.preventDefaultCB >> Callback.when(validKey)(nav.go(AdminPage.Thread(bbs, key.value.trim)))),
            <.label(^.htmlFor := "thread-key", "keyを指定して開く"),
            <.input(^.id := "thread-key", ^.`type` := "text", ^.inputMode := "numeric", ^.className := s"${Styles.input} w-40", ^.value := key.value,
              ^.placeholder := "10桁の数字", ^.onChange ==> ((e: ReactEventFromInput) => key.setState(e.target.value))),
            <.button(^.`type` := "submit", ^.className := Styles.button, ^.disabled := !validKey, "開く")),
          <.p(^.className := s"${Styles.notice} mt-1.5", "削除・移動済みで一覧に無いスレも、keyで開けます。")),
        loaded.state match {
          case LoadState.Loading => <.p(^.className := Styles.notice, "読み込み中…")
          case LoadState.Failed(error) => <.p(^.className := Styles.error, error.message)
          case LoadState.Ready(list) => <.div(
            <.div(^.className := Styles.panel,
              <.h2(^.className := Styles.h2, s"■ 公開中（${list.subject.size}）"),
              if (list.subject.isEmpty) <.p(^.className := Styles.notice, "公開中のスレはありません。")
              else <.table(^.className := "w-full border-collapse text-[13px]",
                <.tbody(list.subject.toVdomArray { thread =>
                  <.tr(^.key := thread.key, ^.className := "hover:bg-[#fafdff]",
                    <.td(^.className := s"$cell w-28 whitespace-nowrap text-[#697785] tabular-nums", thread.key),
                    <.td(^.className := cell, Link(nav, AdminPage.Thread(bbs, thread.key), Styles.link, title(thread.line))))
                }))),
            <.div(^.className := Styles.panel,
              <.h2(^.className := Styles.h2, s"■ 削除・移動済み（${list.removed.size}） ", <.small(^.className := Styles.small, "本文と投稿者ログは保持しています")),
              if (list.removed.isEmpty) <.p(^.className := Styles.notice, "ありません。")
              else <.ul(^.className := "flex list-none flex-wrap gap-x-4 gap-y-1 p-0",
                list.removed.toVdomArray(k => <.li(^.key := k, Link(nav, AdminPage.Thread(bbs, k), Styles.link, k))))))
        })
    }
  }
}
