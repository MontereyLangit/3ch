package io._3ch.common.domain.account

import munit.FunSuite

class LoginThrottleSuite extends FunSuite {
  private val now = 2000000000d
  private val ipA = "a" * 64
  private val ipB = "b" * 64
  private def ip(i: Int): String = f"$i%064x"

  private def failed(state: LoginThrottle, keyIp: String, time: Double): LoginThrottleUpdate =
    state.recordFailure(keyIp, time).toOption.get

  private def failures(state: LoginThrottle, keyIp: String, count: Int, time: Double): LoginThrottle =
    (0 until count).foldLeft(state)((current, _) => failed(current, keyIp, time).state)

  test("null の保存値と接続元ハッシュは例外を出さず不正と判定する") {
    Seq(LoginThrottle(sources = null), LoginThrottle(account = null), LoginThrottle(sources = Map(null.asInstanceOf[String] -> LoginSource(1, now))),
      LoginThrottle(sources = Map(ipA -> null)), LoginThrottle(sources = Map(ipA -> LoginSource(0, now))),
      LoginThrottle(sources = Map(ipA -> LoginSource(1, now, Some(-1d)))), LoginThrottle(account = LoginAccountFailures(-1, now)))
      .foreach(value => assert(!value.valid))
  }

  test("同じ接続元の十回目の失敗でその時刻から一時間だけロックする") {
    val before = (0 until 9).foldLeft(LoginThrottle())((state, i) => failed(state, ipA, now + i).state)
    assert(!before.check(ipA, now + 9).toOption.get.locked)
    val locked = failed(before, ipA, now + 9)
    assertEquals(locked.decision.lockedUntil, Some(now + 9 + 3600))
    assert(locked.state.check(ipA, now + 9 + 3599).toOption.get.locked)
    assert(!locked.state.check(ipA, now + 9 + 3600).toOption.get.locked)
  }

  test("別の接続元の失敗に挟まれても、同じ接続元の十回目でロックする") {
    val others = (1 to 4).map(ip)
    val state = (0 until 10).foldLeft(LoginThrottle()) { (current, i) =>
      val withOthers = others.foldLeft(current)((state, other) => failed(state, other, now + i).state)
      failed(withOthers, ipA, now + i).state
    }
    assert(state.check(ipA, now + 10).toOption.get.locked)
    assert(!state.check(ipB, now + 10).toOption.get.locked)
  }

  test("接続元を回し続けても、百回目の失敗で全接続元を一時間ロックする") {
    val state = (0 until 100).foldLeft(LoginThrottle())((current, i) => failed(current, ip(i % 20), now + i).state)
    assert(state.account.lockedUntil.isDefined)
    assert(state.check(ipB, now + 100).toOption.get.locked)
    assertEquals(state.check(ipB, now + 99 + 3599).map(_.locked), Right(true))
    assertEquals(state.check(ipB, now + 99 + 3600).map(_.locked), Right(false))
    val during = failed(state, ipB, now + 200)
    assertEquals(during.state, state)
    assertEquals(during.decision.lockedUntil, state.account.lockedUntil)
    assertEquals(state.recordSuccess(ipB, now + 201).toOption.get.state, state)
  }

  test("ロック中の接続元が百一件になっても期限内のロックを捨てず、保持できない新しい接続元はロック扱いにする") {
    val locked = (0 until 101).foldLeft(LoginThrottle())((state, i) => failures(state, ip(i), 10, now).copy(account = LoginAccountFailures()))
    assert(locked.sources.size <= LoginThrottle.sourceLimit)
    assertEquals(locked.sources.values.count(_.lockedUntil.isDefined), LoginThrottle.sourceLimit)
    (0 until LoginThrottle.sourceLimit).foreach(i => assert(locked.check(ip(i), now + 1).toOption.get.locked, i.toString))
    val fresh = ip(500)
    assert(locked.check(fresh, now + 1).toOption.get.locked)
    val refused = failed(locked, fresh, now + 1)
    assertEquals(refused.state, locked)
    assert(refused.decision.locked)
    assert(!locked.check(fresh, now + 3600).toOption.get.locked)
  }

  test("期限切れの接続元だけを整理し、成功した接続元の失敗は消すがアカウント全体の失敗数は残す") {
    val state = failures(failures(LoginThrottle(), ipA, 3, now), ipB, 2, now + 3000)
    val later = failed(state, ipB, now + 3600)
    assertEquals(later.state.sources.keySet, Set(ipB))
    assertEquals(later.state.sources(ipB).count, 3)
    assertEquals(later.state.account, LoginAccountFailures(1, now + 3600))
    val success = later.state.recordSuccess(ipB, now + 3601).toOption.get
    assert(!success.decision.locked)
    assertEquals(success.state.sources, Map.empty[String, LoginSource])
    assertEquals(success.state.account.count, 1)
    val reset = failed(state, ipB, now + 7200)
    assertEquals(reset.state.account, LoginAccountFailures(1, now + 7200))
  }

  test("ロック期限は窓とは別に保持し期限中の試行で延長しない") {
    val locked = failures(LoginThrottle(), ipA, 10, now + 3599).copy(account = LoginAccountFailures())
    val during = failed(locked, ipA, now + 3601)
    assertEquals(during.state, locked)
    assertEquals(during.decision.lockedUntil, Some(now + 3599 + 3600))
    val successfulPassword = during.state.recordSuccess(ipA, now + 3602).toOption.get
    assert(successfulPassword.decision.locked)
    assertEquals(successfulPassword.state, locked)
  }

  test("時刻が逆行しても未来側の記録を消さない") {
    val locked = failures(LoginThrottle(), ipA, 10, now)
    val backwards = failed(locked, ipA, now - 100)
    assertEquals(backwards.state, locked)
    assertEquals(backwards.decision.lockedUntil, Some(now + 3600))
    val other = failed(locked, ipB, now - 100)
    assertEquals(other.state.sources(ipA), locked.sources(ipA))
    assertEquals(other.state.account.count, 11)
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
    assertEquals(LoginThrottle(Map(ipA -> LoginSource(1, Double.NaN))).check(ipA, now), Left(LoginThrottleRejection.Invalid))
    assertEquals(LoginThrottle(Map(ipA -> LoginSource(1, now, Some(-1d)))).check(ipA, now), Left(LoginThrottleRejection.Invalid))
  }

  test("試行前の確認は記録を変えず、ロック期限を返す") {
    val five = failures(LoginThrottle(), ipA, 5, now + 1)
    assertEquals(five.check(ipA, now + 6), Right(LoginThrottleDecision(None)))
    assertEquals(five.check(ipB, now + 6), Right(LoginThrottleDecision(None)))
    val locked = failures(five, ipA, 5, now + 10)
    assertEquals(locked.check(ipA, now + 11), Right(LoginThrottleDecision(Some(now + 10 + LoginThrottle.window))))
    assertEquals(locked.check(ipA, now + 11 + LoginThrottle.window).map(_.locked), Right(false))
  }
}
