package io._3ch.bbs.application.posting.ports

import io._3ch.bbs.domain.posting.{ThreadOwner, ThreadPosting, ThreadWriteResult}
import io._3ch.common.domain.{BbsSettings, BoardId}

import scala.concurrent.Future

/** 返信先スレの dat・スレ設定・スレ主。スレ主は読んだときに復号し、壊れていればそこで失敗する */
final case class ThreadSnapshot(dat: String, settings: BbsSettings, owner: () => Option[ThreadOwner])

/** スレの読み書き（Thread DO） */
trait ThreadStore {
  /** スレ立て前に同じ key の dat を読む。旧形式のログは取り込まない */
  def datBeforeCreation(board: BoardId, key: String): Future[String]

  /** 返信先のスレを読む。旧形式のログがあれば取り込む */
  def readForReply(board: BoardId, key: String): Future[ThreadSnapshot]

  /** dat 行と log 行を追記する。拒否は結果で返し、それ以外の障害は Future の失敗 */
  def addRes(board: BoardId, key: String, datLine: String, logLine: String, posting: ThreadPosting): Future[ThreadWriteResult]
}
