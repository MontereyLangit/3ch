package io._3ch.bbs.components

import io._3ch.bbs.generated.IslandComponents
import io._3ch.common.domain.account.SessionView
import io._3ch.common.domain.{BoardId, ThreadCommand, ThreadKey}
import io._3ch.common.islands
import japgolly.scalajs.react.ScalaFnComponent

import scala.scalajs.js.JSConverters.*

case class PostingFormProps(
    bbs: BoardId,
    time: String,
    key: Option[ThreadKey] = None,
    imgurClientId: String = "",
    commands: Seq[ThreadCommand] = ThreadCommand.all,
    authEnabled: Boolean = false,
    session: Option[SessionView] = None,
    requiredLevel: Int = 0,
    returnPath: String = "/"
)

/**
 * 書き込みフォーム
 * （新規スレッド一覧と書き込み欄で共通）
 * 表示・動作はbbs-clientのPostingFormJsが行う
 */
object PostingFormComponent {

  /** islandに渡すJSON互換props */
  def islandProps(props: PostingFormProps): islands.PostingFormProps = new islands.PostingFormProps {
    val bbs = props.bbs.value
    val time = props.time
    val imgurClientId = props.imgurClientId
    val authEnabled = props.authEnabled
    val requiredLevel = props.requiredLevel
    val returnPath = props.returnPath
    val commands = props.commands.map(threadCommand).toJSArray
    val session = props.session.map(sessionProps).orUndefined
    val threadKey = props.key.map(_.value.toString).orUndefined
  }

  private def threadCommand(c: ThreadCommand): islands.ThreadCommandProps = new islands.ThreadCommandProps {
    val text = c.text
    val label = c.label
    val field = c.field
    val description = c.description
  }

  /** ログイン中のユーザーを island へ渡す形にする */
  def sessionProps(s: SessionView): islands.SessionProps = new islands.SessionProps {
    val uid = s.uid
    val lv = s.lv.toDouble
  }

  final val Component = ScalaFnComponent[PostingFormProps] { props =>
    IslandComponents.PostingFormJs(islandProps(props))
  }
}
