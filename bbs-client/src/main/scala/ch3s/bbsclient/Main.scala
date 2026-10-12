package ch3s.bbsclient

import scala.scalajs.js.annotation.JSExportTopLevel

/** ブラウザ向け client.js の export 配線を確認する。bbsClient で独立してリンクする（KB-27）。 */
object Main {

  @JSExportTopLevel("v6ClientPing", "client")
  def v6ClientPing(): String = "pong-from-client"
}
