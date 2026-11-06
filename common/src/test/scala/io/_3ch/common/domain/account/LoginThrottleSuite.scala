package io._3ch.common.domain.account

import munit.FunSuite

class LoginThrottleSuite extends FunSuite {
  private val now = 2000000000d
  private val ipA = "a" * 64
  private val ipB = "b" * 64

  private def failed(state: LoginThrottle, keyIp: String, time: Double): LoginThrottleUpdate =
    state.recordFailure(keyIp, time).toOption.get

  test("null の保存値と接続元ハッシュは例外を出さず不正と判定する") {
    Seq(LoginThrottle(failures = null), LoginThrottle(locks = null), LoginThrottle(failures = Vector(null)),
      LoginThrottle(failures = Vector(LoginFailure(null, 1))), LoginThrottle(locks = Map(null.asInstanceOf[String] -> 1d)))
      .foreach(value => assert(!value.valid))
  }

  test("五回目の失敗を含めて遅延を増やし十二回目から八秒で止める") {
    val updates = (1 to 14).foldLeft(Vector.empty[LoginThrottleUpdate]) { (updates, i) =>
      val state = updates.lastOption.fold(LoginThrottle())(_.state)
      updates :+ failed(state, f"$i%064x", now)
    }
    assertEquals(updates.map(_.decision.delaySeconds), Vector(0, 0, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8, 8, 8))
    assert(updates.forall(update => !update.decision.locked))
  }

  test("同じ接続元の十回目の失敗でその時刻から一時間だけロックする") {
    val before = (0 until 9).foldLeft(LoginThrottle())((state, i) => failed(state, ipA, now + i).state)
    assert(!before.check(ipA, now + 9).toOption.get.locked)
    val locked = failed(before, ipA, now + 9)
    assertEquals(locked.decision.lockedUntil, Some(now + 9 + 3600))
    assert(locked.state.check(ipA, now + 9 + 3599).toOption.get.locked)
    assert(!locked.state.check(ipA, now + 9 + 3600).toOption.get.locked)
  }

  test("別の接続元はロックせずアカウント全体の失敗遅延だけを共有する") {
    val locked = (1 to 10).foldLeft(LoginThrottle())((state, _) => failed(state, ipA, now).state)
    val other = failed(locked, ipB, now + 1)
    assert(!other.decision.locked)
    assertEquals(other.decision.delaySeconds, 7)
    assert(other.state.check(ipA, now + 1).toOption.get.locked)
    val separateAccount = failed(LoginThrottle(), ipA, now + 1)
    assert(!separateAccount.decision.locked)
    assertEquals(separateAccount.decision.delaySeconds, 0)
  }

  test("直近一時間の境界にある失敗は件数から除く") {
    val state = LoginThrottle(Vector.fill(9)(LoginFailure(ipA, now)))
    assert(failed(state, ipA, now + 3599).decision.locked)
    val boundary = failed(state, ipA, now + 3600)
    assert(!boundary.decision.locked)
    assertEquals(boundary.state.failures, Vector(LoginFailure(ipA, now + 3600)))
    assertEquals(boundary.decision.delaySeconds, 0)
  }

  test("ロック期限は失敗履歴の窓とは別に保持し期限中の試行で延長しない") {
    val state = LoginThrottle(Vector.fill(9)(LoginFailure(ipA, now)), Map.empty)
    val locked = failed(state, ipA, now + 3599).state
    val during = failed(locked, ipA, now + 3601)
    assertEquals(during.state.failures, Vector(LoginFailure(ipA, now + 3599)))
    assertEquals(during.decision.lockedUntil, Some(now + 7199))
    val successfulPassword = during.state.recordSuccess(ipA, now + 3602).toOption.get
    assert(successfulPassword.decision.locked)
    assertEquals(successfulPassword.state.locks, locked.locks)
    assertEquals(successfulPassword.state.failures, during.state.failures)
  }

  test("成功時はその接続元の失敗を消し別の接続元のロックと履歴を残す") {
    val locked = (1 to 10).foldLeft(LoginThrottle())((state, _) => failed(state, ipA, now).state)
    val withOther = failed(locked, ipB, now + 1).state
    val success = withOther.recordSuccess(ipB, now + 2).toOption.get
    assert(!success.decision.locked)
    assertEquals(success.decision.delaySeconds, 0)
    assertEquals(success.state.failures.map(_.keyIpHash).toSet, Set(ipA))
    assert(success.state.check(ipA, now + 2).toOption.get.locked)
  }

  test("時刻が逆行しても未来側の失敗とロックを消さない") {
    val locked = (1 to 10).foldLeft(LoginThrottle())((state, _) => failed(state, ipA, now).state)
    val backwards = failed(locked, ipA, now - 100)
    assertEquals(backwards.state, locked)
    assertEquals(backwards.decision.lockedUntil, Some(now + 3600))
    assertEquals(backwards.decision.delaySeconds, 6)
    val other = failed(locked, ipB, now - 100)
    assertEquals(other.state.failures.size, 11)
    assertEquals(other.decision.delaySeconds, 7)
  }

  test("不正な時刻・接続元・保存状態では計数を進めない") {
    Seq(Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity, -1d, now + 0.5).foreach { time =>
      assertEquals(LoginThrottle().check(ipA, time), Left(LoginThrottleRejection.Invalid))
      assertEquals(LoginThrottle().recordFailure(ipA, time), Left(LoginThrottleRejection.Invalid))
      assertEquals(LoginThrottle().recordSuccess(ipA, time), Left(LoginThrottleRejection.Invalid))
    }
    Seq(null, "raw-ip").foreach { key =>
      assertEquals(LoginThrottle().check(key, now), Left(LoginThrottleRejection.Invalid))
      assertEquals(LoginThrottle().recordFailure(key, now), Left(LoginThrottleRejection.Invalid))
      assertEquals(LoginThrottle().recordSuccess(key, now), Left(LoginThrottleRejection.Invalid))
    }
    assertEquals(LoginThrottle(Vector(LoginFailure(ipA, Double.NaN))).check(ipA, now), Left(LoginThrottleRejection.Invalid))
    assertEquals(LoginThrottle(locks = Map(ipA -> -1d)).check(ipA, now), Left(LoginThrottleRejection.Invalid))
  }

  test("失敗とロックはアカウントごとに上限まで残す") {
    val start = 2000000000d
    val throttle = (0 until 150).foldLeft(LoginThrottle()) { (state, i) =>
      val key = f"$i%064x"
      (0 until 10).foldLeft(state)((current, n) => current.recordFailure(key, start + i + n * 0.0).toOption.get.state)
    }
    assert(throttle.failures.size <= LoginThrottle.failureLimit)
    assert(throttle.locks.size <= LoginThrottle.lockLimit)
  }
}
