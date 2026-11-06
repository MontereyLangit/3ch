package io._3ch.bbs.repository

import io._3ch.bbs.application.posting.ports.SettingsStore
import io._3ch.bbs.external.cloudflare.WorkerEnv
import io._3ch.common.domain.{BbsSettings, BbsSubjects, BoardId}

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

/** 板のスレ一覧・設定・案内文を DO と R2 から読む */
object BoardRepository {
  /** Board DO からスレ一覧を読む */
  def getSubjects(board: BoardId, e: WorkerEnv): Future[BbsSubjects] =
    e.BOARD_DO.get(e.BOARD_DO.idFromName(board.value)).getSubject().toFuture.map(BbsSubjects.create)

  /** R2 の SETTING.TXT を読む。無ければ既定値を使う */
  def getBoardSettings(board: BoardId, e: WorkerEnv): Future[BbsSettings] =
    e.ch3www.get(board.value + "/SETTING.TXT").toFuture.flatMap { obj =>
      if (obj == null) Future.successful(BbsSettings.defaultText) else obj.text().toFuture
    }.map(BbsSettings.create(_, board))

  /** R2 の head.txt を読む。無ければ空文字列 */
  def getHeadText(board: BoardId, e: WorkerEnv): Future[String] =
    e.ch3www.get(board.value + "/head.txt").toFuture.flatMap { obj =>
      if (obj == null) Future.successful("") else obj.text().toFuture
    }

  /** 投稿処理のポート */
  def settingsStore(e: WorkerEnv): SettingsStore = board => getBoardSettings(board, e)
}
