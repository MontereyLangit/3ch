package ch3s.bbs.components

import japgolly.scalajs.react.vdom.html_<^.*

import scala.scalajs.js.URIUtils

object BoardSettingsComponent {
  def content(bbs: String, settings: Map[String, String]): VdomElement = {
    <.div(
      <.pre(^.className := "m-0 whitespace-pre-wrap font-mono text-xs", settings.map { case (k, v) => s"$k=$v" }.mkString("\n")),
      <.a(^.className := "text-[#06f] hover:underline", ^.href := s"/${URIUtils.encodeURIComponent(bbs)}/SETTING.TXT", "SETTING.TXT")
    )
  }
}
