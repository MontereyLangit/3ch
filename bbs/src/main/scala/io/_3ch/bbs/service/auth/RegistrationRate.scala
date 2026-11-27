package io._3ch.bbs.service.auth

import io._3ch.bbs.service.auth.ports.AbuseReviewLog
import io._3ch.common.domain.account.{AbuseReason, AbuseThresholds, AuthRateCounts}
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.control.NonFatal

/** 登録の接続元ごとの上限。新規登録と方式の移行は同じ計数（register）を使う。超えたら「要確認」に残して拒否する */
object RegistrationRate {
  def check(rate: (String, String, Double) => Future[AuthRateCounts], review: AbuseReviewLog, keyIp: String, now: Double, strict: Boolean): Future[Unit] =
    rate(keyIp, "register", now).flatMap { counts =>
      if (AbuseThresholds.registration(counts, strict)) Future.unit
      else review.flag(keyIp, AbuseReason.RegisterBurst, counts, now).recover { case NonFatal(_) => () }
        .flatMap(_ => Future.failed(AuthServiceException(AuthServiceFailure.RateLimited)))
    }
}
