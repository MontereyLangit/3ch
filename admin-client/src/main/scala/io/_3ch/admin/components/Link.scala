package io._3ch.admin.components

import io._3ch.admin.{AdminPage, Nav}
import japgolly.scalajs.react.*
import japgolly.scalajs.react.vdom.html_<^.*

/** 画面内のリンク。修飾キー付きのクリックはブラウザーに任せる */
object Link {
  def apply(nav: Nav, page: AdminPage, className: String, content: VdomNode*): VdomElement =
    <.a(^.href := page.path, ^.className := className,
      ^.onClick ==> { (e: ReactMouseEvent) =>
        if (e.ctrlKey || e.metaKey || e.shiftKey || e.altKey || e.button != 0) Callback.empty
        else e.preventDefaultCB >> nav.go(page)
      },
      content.toTagMod)
}
