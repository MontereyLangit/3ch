package io._3ch.bbs.application.posting.ports

import io._3ch.common.domain.{BbsSettings, BoardId}

import scala.concurrent.Future

/** 板設定（SETTING.TXT） */
trait SettingsStore {
  /** 板設定。無ければ既定の設定 */
  def boardSettings(board: BoardId): Future[BbsSettings]
}
