package io._3ch.bbs.application.auth.ports

import io._3ch.common.domain.account.{AbuseReason, AuthRateCounts}
import scala.concurrent.Future

/** 乱造検知の「要確認」の記録先。記録の失敗は認証の結果に含めない */
trait AbuseReviewLog {
  def flag(keyIpHash: String, reason: AbuseReason, counts: AuthRateCounts, now: Double): Future[Unit]
}

object AbuseReviewLog {
  /** 何も記録しない */
  val none: AbuseReviewLog = (_, _, _, _) => Future.unit
}
