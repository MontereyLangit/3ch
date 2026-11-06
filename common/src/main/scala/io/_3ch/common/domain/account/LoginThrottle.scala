package io._3ch.common.domain.account

/** 同じアカウントで失敗した接続元の HMAC と UNIX 秒 */
final case class LoginFailure(keyIpHash: String, t: Double)

/** ログイン計数へ渡す時刻・接続元・保存状態の不正 */
enum LoginThrottleRejection {
  case Invalid
}

/** 接続元のロックと失敗応答の遅延。暗号計算と待機は呼び出し元で行う */
final case class LoginThrottleDecision(lockedUntil: Option[Double], delaySeconds: Int) {
  def locked: Boolean = lockedUntil.isDefined
}

/** 計数後の状態と、その試行に適用するロック・遅延 */
final case class LoginThrottleUpdate(state: LoginThrottle, decision: LoginThrottleDecision)

/** 一アカウントの直近一時間の失敗と接続元別ロック。時刻逆行では未来側の記録を残す */
final case class LoginThrottle(failures: Vector[LoginFailure] = Vector.empty, locks: Map[String, Double] = Map.empty) {
  def valid: Boolean = failures != null && locks != null &&
    failures.forall(failure => failure != null && failure.keyIpHash != null && DedicatedBrowserTokens.validHash(failure.keyIpHash) && AccountProfile.validTime(failure.t)) &&
    locks.forall { case (hash, until) => hash != null && DedicatedBrowserTokens.validHash(hash) && AccountProfile.validTime(until) }

  private def retained(now: Double): LoginThrottle = copy(failures = failures.filter(_.t > now - LoginThrottle.window), locks = locks.filter(_._2 > now))

  private def decision(keyIpHash: String): LoginThrottleDecision =
    LoginThrottleDecision(locks.get(keyIpHash), math.min(8, math.max(0, failures.size - 4)))

  private def current(keyIpHash: String, now: Double): Either[LoginThrottleRejection, LoginThrottle] =
    if (!valid || keyIpHash == null || !DedicatedBrowserTokens.validHash(keyIpHash) || !AccountProfile.validTime(now)) Left(LoginThrottleRejection.Invalid)
    else Right(retained(now))

  /** 試行前の状態を返す。ロック中も呼び出し元で同じ暗号計算を行う */
  def check(keyIpHash: String, now: Double): Either[LoginThrottleRejection, LoginThrottleDecision] =
    current(keyIpHash, now).map(_.decision(keyIpHash))

  /** 今回を含む十回目の失敗で一時間ロックする。ロック中の試行は記録と期限を増やさない */
  def recordFailure(keyIpHash: String, now: Double): Either[LoginThrottleRejection, LoginThrottleUpdate] =
    current(keyIpHash, now).map { state =>
      if (state.locks.contains(keyIpHash)) LoginThrottleUpdate(state, state.decision(keyIpHash))
      else {
        val failures = state.failures :+ LoginFailure(keyIpHash, now)
        val locks = if (failures.count(_.keyIpHash == keyIpHash) >= 10) state.locks.updated(keyIpHash, now + LoginThrottle.window) else state.locks
        val next = LoginThrottle(failures, locks)
        LoginThrottleUpdate(next, next.decision(keyIpHash))
      }
    }

  /** 成功した接続元の失敗だけを消す。他の接続元と、まだロック中の試行は変更しない */
  def recordSuccess(keyIpHash: String, now: Double): Either[LoginThrottleRejection, LoginThrottleUpdate] =
    current(keyIpHash, now).map { state =>
      if (state.locks.contains(keyIpHash)) LoginThrottleUpdate(state, state.decision(keyIpHash))
      else {
        val next = state.copy(failures = state.failures.filterNot(_.keyIpHash == keyIpHash))
        LoginThrottleUpdate(next, LoginThrottleDecision(None, 0))
      }
    }
}

object LoginThrottle {
  val window: Double = 3600
}
