package io._3ch.common.islands

import io._3ch.common.validation.{JsField, JsKind, JsShape}
import io._3ch.common.validation.JsShape.{optional, required}

import scala.scalajs.js

/** ログイン中のユーザー。lv は JSON の数値として受け取る */
trait SessionProps extends js.Object {
  val uid: String
  val lv: Double
}

object SessionProps {
  val fields: List[JsField] = List(required("uid", JsKind.Str), required("lv", JsKind.Num))
  def validate(raw: js.Any): Either[String, SessionProps] = JsShape.validate(raw, fields)
}

/** スレ立てのコマンド補助の 1 件 */
trait ThreadCommandProps extends js.Object {
  val text: String
  val label: String
  val field: String
  val description: String
}

/** PostingFormJs の props。threadKey が無ければ新規スレッド作成のフォーム */
trait PostingFormProps extends js.Object {
  val bbs: String
  val time: String
  val imgurClientId: String
  val authEnabled: Boolean
  val requiredLevel: Int
  val returnPath: String
  val commands: js.Array[ThreadCommandProps]
  val session: js.UndefOr[SessionProps]
  val threadKey: js.UndefOr[String]
}

object PostingFormProps {
  private val command = JsKind.Obj(List("text", "label", "field", "description").map(required(_, JsKind.Str)))
  val fields: List[JsField] = List(
    required("bbs", JsKind.Str), required("time", JsKind.Str), required("imgurClientId", JsKind.Str),
    required("authEnabled", JsKind.Bool), required("requiredLevel", JsKind.Int), required("returnPath", JsKind.Str),
    required("commands", JsKind.Arr(command)), optional("session", JsKind.Obj(SessionProps.fields)), optional("threadKey", JsKind.Str)
  )
  def validate(raw: js.Any): Either[String, PostingFormProps] = JsShape.validate(raw, fields)
}

/** BoardDetailsJs の props。新規スレッド作成のフォームの props は postingForm に入れ子で持つ */
trait BoardDetailsProps extends js.Object {
  val bbs: String
  val headText: String
  val settingsText: String
  val postingForm: PostingFormProps
}

object BoardDetailsProps {
  val fields: List[JsField] = List(
    required("bbs", JsKind.Str), required("headText", JsKind.Str), required("settingsText", JsKind.Str),
    required("postingForm", JsKind.Obj(PostingFormProps.fields))
  )
  def validate(raw: js.Any): Either[String, BoardDetailsProps] = JsShape.validate(raw, fields)
}

/** ThreadListJs の props。subjectText は subject.txt の本文、now はサーバーで描画したときのミリ秒 */
trait ThreadListProps extends js.Object {
  val bbs: String
  val `type`: String
  val subjectText: String
  val query: String
  val sort: String
  val now: Double
}

object ThreadListProps {
  val fields: List[JsField] =
    List("bbs", "type", "subjectText", "query", "sort").map(required(_, JsKind.Str)) :+ required("now", JsKind.Num)
  def validate(raw: js.Any): Either[String, ThreadListProps] = JsShape.validate(raw, fields)
}

/** AccountNavJs の props */
trait AccountNavProps extends js.Object {
  val returnPath: String
  val session: js.UndefOr[SessionProps]
}

object AccountNavProps {
  val fields: List[JsField] = List(required("returnPath", JsKind.Str), optional("session", JsKind.Obj(SessionProps.fields)))
  def validate(raw: js.Any): Either[String, AccountNavProps] = JsShape.validate(raw, fields)
}

/** SiteHeaderJs の props。authEnabled ならアカウントの欄に AccountNav を出す */
trait SiteHeaderProps extends AccountNavProps {
  val authEnabled: Boolean
}

object SiteHeaderProps {
  val fields: List[JsField] = required("authEnabled", JsKind.Bool) :: AccountNavProps.fields
  def validate(raw: js.Any): Either[String, SiteHeaderProps] = JsShape.validate(raw, fields)
}
