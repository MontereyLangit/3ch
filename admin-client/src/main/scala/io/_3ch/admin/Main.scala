package io._3ch.admin

import japgolly.scalajs.react.ReactDOMClient
import org.scalajs.dom

import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel

/** 管理画面の起動。起動ページの #admin-root に描画する */
object Main {
  def main(args: Array[String]): Unit =
    if (js.typeOf(js.Dynamic.global.document) != "undefined") Option(dom.document.getElementById("admin-root")).foreach(mount)

  /** container に管理画面を描画し、外す関数を返す（テストからも呼ぶ） */
  @JSExportTopLevel("mountAdmin")
  def mount(container: dom.Element): js.Function0[Unit] = {
    val root = ReactDOMClient.createRoot(container)
    root.render(AdminApp.Component())
    () => root.unmount()
  }
}
