package ch3s.bbs.components

import japgolly.scalajs.react.ScalaFnComponent
import japgolly.scalajs.react.vdom.html_<^.*

case class BoardIndexProps(title: String, headText: String)

/**
 * 板top
 */
object BoardIndexComponent {
  final val Component = ScalaFnComponent[BoardIndexProps] { props =>
    <.main(
      <.h1(props.title),
      <.hr,
      <.div(^.dangerouslySetInnerHtml := props.headText) // TODO: head.txtは共通部分かも
      // TODO: スレ一覧
      // TODO: スレ立てフォーム
      // TODO: 板設定閲覧
    )
  }
}
