package io._3ch.common.domain.account

/** 接続元ごとの失敗数と窓の開始時刻（UNIX 秒）。lockedUntil があればその時刻までロック中 */
final case class LoginSource(count: Int, since: Double, lockedUntil: Option[Double] = None) {
  /** ロック中はロック期限、そうでなければ窓の終わりまで保持する */
  def expiresAt: Double = lockedUntil.getOrElse(since + LoginThrottle.window)
  def valid: Boolean = count >= 1 && count <= LoginThrottle.sourceFailureLimit && AccountProfile.validTime(since) && lockedUntil.forall(AccountProfile.validTime)
}

/** 一アカウント全体の失敗数と窓の開始時刻。count が 0 のときは窓を持たない */
final case class LoginAccountFailures(count: Int = 0, since: Double = 0, lockedUntil: Option[Double] = None) {
  def valid: Boolean = count >= 0 && count <= LoginThrottle.accountFailureLimit && AccountProfile.validTime(since) && lockedUntil.forall(AccountProfile.validTime)
}

/** ログイン計数へ渡す時刻・接続元・保存状態の不正 */
enum LoginThrottleRejection {
  case Invalid
}

/** 試行を拒否する期限。ロック中でなければ None。暗号計算は呼び出し元でロックの有無にかかわらず行う */
final case class LoginThrottleDecision(lockedUntil: Option[Double]) {
  def locked: Boolean = lockedUntil.isDefined
}

/** 計数後の状態と、その試行に適用するロック */
final case class LoginThrottleUpdate(state: LoginThrottle, decision: LoginThrottleDecision)

/**
 * 一アカウントの接続元別の失敗数・ロックと、アカウント全体の失敗数。期限切れ以外の記録は捨てず、
 * 保持できる接続元が尽きたときは新しい接続元をロック扱いにする。時刻逆行では未来側の記録を残す
 */
final case class LoginThrottle(sources: Map[String, LoginSource] = Map.empty, account: LoginAccountFailures = LoginAccountFailures()) {
  def valid: Boolean = sources != null && account != null && sources.size <= LoginThrottle.sourceLimit && account.valid &&
    sources.forall { case (hash, source) => hash != null && DedicatedBrowserTokens.validHash(hash) && source != null && source.valid }

  private def retained(now: Double): LoginThrottle = {
    val kept = sources.filter(_._2.expiresAt > now)
    val locked = account.lockedUntil.filter(_ > now)
    val windowOpen = account.count > 0 && account.since + LoginThrottle.window > now
    copy(sources = kept, account = if (locked.isDefined || windowOpen) account.copy(lockedUntil = locked) else LoginAccountFailures())
  }

  private def current(keyIpHash: String, now: Double): Either[LoginThrottleRejection, LoginThrottle] =
    if (!valid || keyIpHash == null || !DedicatedBrowserTokens.validHash(keyIpHash) || !AccountProfile.validTime(now)) Left(LoginThrottleRejection.Invalid)
    else Right(retained(now))

  private def full(keyIpHash: String): Boolean = !sources.contains(keyIpHash) && sources.size >= LoginThrottle.sourceLimit

  private def decision(keyIpHash: String): LoginThrottleDecision = {
    val untilFull = Option.when(full(keyIpHash))(sources.values.map(_.expiresAt).min)
    LoginThrottleDecision(Seq(account.lockedUntil, sources.get(keyIpHash).flatMap(_.lockedUntil), untilFull).flatten.maxOption)
  }

  /** 試行前の状態を返す。ロック中も呼び出し元で同じ暗号計算を行う */
  def check(keyIpHash: String, now: Double): Either[LoginThrottleRejection, LoginThrottleDecision] =
    current(keyIpHash, now).map(_.decision(keyIpHash))

  /**
   * 同じ接続元の十回目の失敗で一時間ロックし、アカウント全体の百回目の失敗で全接続元を一時間ロックする。
   * ロック中の試行は記録と期限を増やさない
   */
  def recordFailure(keyIpHash: String, now: Double): Either[LoginThrottleRejection, LoginThrottleUpdate] =
    current(keyIpHash, now).map { state =>
      if (state.decision(keyIpHash).locked) LoginThrottleUpdate(state, state.decision(keyIpHash))
      else {
        val previous = state.sources.getOrElse(keyIpHash, LoginSource(0, now))
        val count = previous.count + 1
        val source = previous.copy(count = count, lockedUntil = Option.when(count >= LoginThrottle.sourceFailureLimit)(now + LoginThrottle.window))
        val total = if (state.account.count == 0) LoginAccountFailures(1, now) else state.account.copy(count = state.account.count + 1)
        val account = total.copy(lockedUntil = Option.when(total.count >= LoginThrottle.accountFailureLimit)(now + LoginThrottle.window))
        val next = LoginThrottle(state.sources.updated(keyIpHash, source), account)
        LoginThrottleUpdate(next, next.decision(keyIpHash))
      }
    }

  /** 成功した接続元の失敗だけを消す。他の接続元・アカウント全体の失敗数と、まだロック中の試行は変更しない */
  def recordSuccess(keyIpHash: String, now: Double): Either[LoginThrottleRejection, LoginThrottleUpdate] =
    current(keyIpHash, now).map { state =>
      if (state.decision(keyIpHash).locked) LoginThrottleUpdate(state, state.decision(keyIpHash))
      else LoginThrottleUpdate(state.copy(sources = state.sources - keyIpHash), LoginThrottleDecision(None))
    }
}

object LoginThrottle {
  val window: Double = 3600
  /** 同じ接続元がこの回数失敗すると一時間ロックする */
  val sourceFailureLimit: Int = 10
  /** 一アカウントで同時に保持する接続元の最大数 */
  val sourceLimit: Int = 100
  /** 全接続元の失敗がこの回数に達すると一時間、すべての接続元をロックする */
  val accountFailureLimit: Int = 100
}
