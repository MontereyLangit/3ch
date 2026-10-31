package ch3s.bbs.components.layout

import ch3s.bbs.BuildInfo
import japgolly.scalajs.react.ScalaFnComponent
import japgolly.scalajs.react.vdom.html_<^.*

/** バージョンと更新日を表示する共通フッター */
object FooterComponent {
  final val Component = ScalaFnComponent[Unit] { _ =>
    <.footer(^.className := "site-footer px-2 py-3 text-center text-xs",
      <.a(^.className := "text-[#06f] hover:underline", ^.href := "https://github.com/MontereyLangit/ch3s", "ch3s"),
      s" ver ${BuildInfo.version} ${BuildInfo.updatedDate} ",
      <.a(^.href := "https://github.com/MontereyLangit/", ^.textDecoration := "none", ^.color := "rgb(254, 100, 46)", ^.fontWeight := "700", "†Monterey Langit ★")
    )
  }
}
