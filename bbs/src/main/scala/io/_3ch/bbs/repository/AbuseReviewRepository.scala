package io._3ch.bbs.repository

import io._3ch.bbs.application.auth.ports.{AbuseReviewLog, AccountStoreException}
import io._3ch.bbs.external.cloudflare.WorkerEnv
import io._3ch.common.domain.account.{AbuseReason, AbuseReview, AccountRejection, AuthRateCounts}
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

/** 乱造検知の「要確認」一覧。名前が review の AuthRate に置く（AccountDirectory を作るまでの記録先） */
final class AbuseReviewRepository(env: WorkerEnv) extends AbuseReviewLog {
  def flag(keyIpHash: String, reason: AbuseReason, counts: AuthRateCounts, now: Double): Future[Unit] =
    env.AUTH_RATE_DO.get(env.AUTH_RATE_DO.idFromName(AbuseReview.name)).flag(keyIpHash, reason.code, counts.hour, counts.day, counts.distinctDevices24h, now)
      .toFuture.map(code => if (code.nonEmpty) throw AccountStoreException(AccountRejection.values.find(_.code == code).getOrElse(AccountRejection.Invalid)))
}
