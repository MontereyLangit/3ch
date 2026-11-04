package io._3ch.bbs.handler

import japgolly.scalajs.react.facade.React
import scala.scalajs.js

/** 専ブラ向け板一覧。カテゴリ見出しと板リンク・改行の並びを維持する。 */
object BbsMenuPage {
  private def element(tag: String, children: React.Node*): React.Element = {
    React.createElement(tag, new js.Object(), children*)
  }

  def render(boards: Seq[(String, String)], updated: String): React.Element = {
    def link(href: String, label: String, newWindow: Boolean = false): React.Element = {
      val props = js.Dictionary[js.Any]("href" -> href)
      if newWindow then props("target") = "_blank"
      React.createElement("a", props.asInstanceOf[js.Object], label)
    }
    val entries: Seq[React.Node] = boards.flatMap { case (board, title) =>
      Seq[React.Node](link(s"https://www.3ch.io/$board/", title), element("br"), "\n")
    }
    val menu = React.createElement("font", js.Dictionary("size" -> "2").asInstanceOf[js.Object], (
      Seq[React.Node](
        link("https://www.3ch.io/", "3chの入口", true), element("br"),
        link("https://www.3ch.io/html/guide/", "3ch総合案内", true), element("br"),
        link("https://wiki.3chbbs.com/", "3ch Wiki", true), element("br"),
        element("br"), element("br"), element("b", "３ちゃんねる"), element("br"), "\n"
      ) ++ entries ++ Seq[React.Node](
        element("br"), element("br"), element("b", "他のサイト"), element("br"),
        element("br"), s"更新日 $updated"
      )
    )*)
    val head = element("head",
      React.createElement("meta", js.Dictionary("httpEquiv" -> "Content-Type", "content" -> "text/html; charset=Shift_JIS").asInstanceOf[js.Object]),
      element("title", "BBS MENU for 3ch"), RendererShell.clientScript()
    )
    val body = React.createElement("body", js.Dictionary(
      "text" -> "#CC3300", "bgcolor" -> "#FFFFFF", "link" -> "#0000FF", "alink" -> "#ff0000", "vlink" -> "#660099"
    ).asInstanceOf[js.Object], menu)
    element("html", head, body)
  }
}
