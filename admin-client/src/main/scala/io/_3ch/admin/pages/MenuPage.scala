package io._3ch.admin.pages

import io._3ch.admin.Nav
import japgolly.scalajs.react.vdom.html_<^.*

/** 板一覧（bbsmenu.html） */
object MenuPage {
  def apply(nav: Nav): VdomElement = TextFilePage.Component(TextFilePage.Props(nav, "板一覧", "bbsmenu.html", "/menu/", "bbsmenu.html",
    <.p(<.b("書き方"), ": 公開する bbsmenu.html を HTML で書きます。保存した後はこの内容を配信し、自動生成は使いません。")))
}
