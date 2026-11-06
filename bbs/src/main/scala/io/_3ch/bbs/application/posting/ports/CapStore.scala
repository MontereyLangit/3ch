package io._3ch.bbs.application.posting.ports

import io._3ch.common.domain.posting.CapHandle

import scala.concurrent.Future

/** キャップの照合 */
trait CapStore {
  /** 照合キーに対応するキャップ。未登録なら None */
  def find(key: String): Future[Option[CapHandle]]
}
