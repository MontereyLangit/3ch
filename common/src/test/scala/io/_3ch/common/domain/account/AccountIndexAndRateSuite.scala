package io._3ch.common.domain.account

import munit.FunSuite

class AccountIndexAndRateSuite extends FunSuite {
  private val now = 2000000000d
  private val first = "abcdefghijklmnop"
  private val second = "qrstuvwxyz012345"

  test("期限切れ予約を取り直した後は以前の所有者が確定も削除もできない") {
    val pending = AccountIndexEntry.register(None, first, now, None, AccountIndexKind.Uid).toOption.get
    assertEquals(AccountIndexEntry.register(Some(pending), second, now + 599, None, AccountIndexKind.Uid), Left(AccountRejection.IndexConflict))
    val reclaimed = AccountIndexEntry.register(Some(pending), second, now + 600, None, AccountIndexKind.Uid).toOption.get
    assertEquals(reclaimed.confirm(first, now + 601), Left(AccountRejection.IndexOwnership))
    assertEquals(reclaimed.remove(AccountIndexKind.Uid, first), Left(AccountRejection.IndexOwnership))
    assertEquals(pending.confirm(second, now + 600), Left(AccountRejection.IndexOwnership))
    val confirmed = reclaimed.confirm(second, now + 601).toOption.get
    assertEquals(confirmed.remove(AccountIndexKind.Uid, second), Left(AccountRejection.IndexPermanent))
    assertEquals(AccountIndexEntry.register(Some(confirmed), first, now + 999999, None, AccountIndexKind.Uid), Left(AccountRejection.IndexConflict))
  }

  test("予約が期限切れでも、他の所有者に取られていなければ同じ所有者は確定できる") {
    val pending = AccountIndexEntry.register(None, first, now, None, AccountIndexKind.Uid).toOption.get
    assert(!pending.reserved(now + 600))
    assertEquals(pending.confirm(first, now + 99999), Right(pending.copy(confirmed = true)))
    assertEquals(pending.confirm(second, now + 99999), Left(AccountRejection.IndexOwnership))
  }

  test("token 索引だけがキー ID を必須とする") {
    assertEquals(AccountIndexEntry.register(None, first, now, None, AccountIndexKind.Token), Left(AccountRejection.Invalid))
    assert(AccountIndexEntry.register(None, first, now, Some("token1"), AccountIndexKind.Token).isRight)
    assertEquals(AccountIndexEntry.register(None, first, now, Some("token1"), AccountIndexKind.Email), Left(AccountRejection.Invalid))
    assertEquals(AccountIndexEntry(first, now).remove(AccountIndexKind.Uid, first), Right(()))
  }

  test("認証計数は境界の古い履歴を捨てて同じ端末を一回だけ数える") {
    val history = AuthRateHistory(Vector(AuthRateEvent("verify", now - 86400), AuthRateEvent("verify", now - 3600),
      AuthRateEvent("verify", now - 3599), AuthRateEvent("login", now - 1),
      AuthRateEvent("deviceHash:p" + "a" * 18, now - 20), AuthRateEvent("deviceHash:p" + "a" * 18, now - 10),
      AuthRateEvent("deviceHash:g" + "b" * 18, now - 1)))
    val (next, counts) = history.hit("verify", now).toOption.get
    assertEquals(counts, AuthRateCounts(2, 3, 2))
    assert(!next.events.exists(_.t == now - 86400))
    assertEquals(next.hit("other", now), Left(AccountRejection.Invalid))
    assert(next.hit("token", now).isRight)
    assertEquals(next.hit("verify", Double.PositiveInfinity), Left(AccountRejection.Invalid))
  }

  test("上限に達した種類は記録を増やさず、閾値を超えた件数を返し続ける") {
    val full = (0 until AuthRateHistory.kindLimit).foldLeft(AuthRateHistory())((history, i) => history.hit("login", now - 80000 + i).toOption.get._1)
    assertEquals(full.events.size, AuthRateHistory.kindLimit)
    val (same, counts) = full.hit("login", now).toOption.get
    assertEquals(same.events.size, AuthRateHistory.kindLimit)
    assert(!AbuseThresholds.login(counts))
    val (_, later) = same.hit("login", now + 3000).toOption.get
    assert(!AbuseThresholds.login(later))
    val flood = (0 until 1000).foldLeft(AuthRateHistory())((history, i) => history.hit("loginFail", now + i).toOption.get._1)
    assertEquals(flood.events.size, AuthRateHistory.kindLimit)
    val (aged, fresh) = full.hit("login", now + 86400).toOption.get
    assertEquals((aged.events.size, fresh.day), (1, 1))
  }

  test("端末は同じハッシュを一件にまとめ、申告の無い端末は毎回別の端末として数え、記録は上限で止める") {
    val device = "deviceHash:p" + "a" * 18
    val twice = AuthRateHistory().hit(device, now).toOption.get._1.hit(device, now + 1).toOption.get
    assertEquals((twice._1.events.size, twice._2.distinctDevices24h), (1, 1))
    val anonymous = (0 until 6).foldLeft((AuthRateHistory(), AuthRateCounts(0, 0, 0))) { case ((history, _), i) =>
      history.hit(AuthRateHistory.anonymousDevice, now + i).toOption.get
    }
    assertEquals(anonymous._2.distinctDevices24h, 6)
    assert(!AbuseThresholds.verify(anonymous._2))
    val flood = (0 until 100).foldLeft((AuthRateHistory(), AuthRateCounts(0, 0, 0))) { case ((history, _), i) =>
      history.hit(AuthRateHistory.anonymousDevice, now + i).toOption.get
    }
    assertEquals(flood._1.events.size, AuthRateHistory.deviceLimit)
    assert(flood._2.distinctDevices24h > 5)
  }

  test("乱造検知は verify 回数と相異なる端末数の両方で制限する") {
    assert(AbuseThresholds.verify(AuthRateCounts(10, 100, 5)))
    assert(!AbuseThresholds.verify(AuthRateCounts(11, 100, 5)))
    assert(!AbuseThresholds.verify(AuthRateCounts(1, 1, 6)))
    assert(AbuseThresholds.tokenIssue(AuthRateCounts(10, 10, 0)))
    assert(!AbuseThresholds.tokenIssue(AuthRateCounts(1, 11, 0)))
    assert(!AbuseThresholds.registration(AuthRateCounts(1, 6, 0)))
    assert(!AbuseThresholds.login(AuthRateCounts(31, 31, 0)))
    assert(!AbuseThresholds.reset(AuthRateCounts(1, 4, 0)))
  }
}
