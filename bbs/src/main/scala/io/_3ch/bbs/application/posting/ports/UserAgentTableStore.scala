package io._3ch.bbs.application.posting.ports

import io._3ch.bbs.domain.posting.UserAgentTable

import scala.concurrent.Future

/** 識別子の phoebe 書式で使う UA 解析表 */
trait UserAgentTableStore {
  /** 表を読む。表が無いか読めなければ空の表 */
  def load(): Future[UserAgentTable]
}

object UserAgentTableStore {
  /** 表を持たない環境で使う */
  val empty: UserAgentTableStore = () => Future.successful(UserAgentTable.empty)
}
