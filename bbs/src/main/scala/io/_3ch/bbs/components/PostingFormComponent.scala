package io._3ch.bbs.components

import io._3ch.bbs.generated.IslandComponents
import io._3ch.common.domain.{BoardId, ThreadCommand, ThreadKey}
import japgolly.scalajs.react.ScalaFnComponent

import scala.scalajs.js

case class PostingFormProps(
  bbs: BoardId,
  time: String,
  key: Option[ThreadKey] = None,
  imgurClientId: String = "",
  commands: Seq[ThreadCommand] = ThreadCommand.all
)

/**
 * 書き込みフォーム
 * （新規スレッド一覧と書き込み欄で共通）
 * 表示・動作はbbs-clientのPostingFormJsが行う
 */
object PostingFormComponent {

  /** islandに渡すJSON互換props */
  def islandProps(props: PostingFormProps): js.Object = {
    val result = js.Dynamic.literal(bbs = props.bbs.value, time = props.time, imgurClientId = props.imgurClientId,
      commands = js.Array(props.commands.map(c =>
        js.Dynamic.literal(text = c.text, label = c.label, field = c.field, description = c.description)
      )*))
    props.key.foreach(key => result.updateDynamic("threadKey")(key.value.toString))
    result.asInstanceOf[js.Object]
  }

  final val Component = ScalaFnComponent[PostingFormProps] { props =>
    IslandComponents.PostingFormJs(islandProps(props))
  }
}
