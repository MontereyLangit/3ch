package io._3ch.bbs.application.admin.ports

import io._3ch.common.admin.{TextFile, ThreadAction}

import scala.concurrent.Future

/** R2 へ書くときの条件 */
enum WriteCondition {
  /** ETag が一致するときだけ */
  case Matches(etag: String)
  /** ファイルが無いときだけ */
  case Absent
}

/** 条件付き書き込みの結果 */
enum WriteResult {
  case Written(etag: Option[String])
  case Conflict
}

/** 管理画面が読み書きする R2 のテキストファイル */
trait AdminFiles {
  /** 無ければ None */
  def read(path: String): Future[Option[TextFile]]

  def write(path: String, text: String, condition: WriteCondition): Future[WriteResult]

  /** 最上位の「/」区切りの接頭辞（末尾の / を除く）。全ページを辿る */
  def topPrefixes(): Future[List[String]]
}

/** Thread DO の inspect の結果。無い項目は空か偽 */
final case class ThreadRecord(dat: String, log: String, legacyLog: Option[String], settings: String, hidden: Boolean, stopped: Boolean,
  movedTo: Option[String], movingTo: Option[String])

/** 管理操作で使う Board / Thread DO */
trait AdminThreads {
  def subject(bbs: String): Future[String]

  def removed(bbs: String): Future[List[String]]

  /** 旧データの取り込みを済ませて状態を読む */
  def inspect(bbs: String, key: String): Future[ThreadRecord]

  /** DO が拒否したら Left にその文言 */
  def moderate(bbs: String, key: String, action: ThreadAction): Future[Either[String, Unit]]
}
