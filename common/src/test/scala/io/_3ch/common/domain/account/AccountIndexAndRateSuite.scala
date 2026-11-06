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
    assertEquals(pending.confirm(first, now + 600), Left(AccountRejection.IndexOwnership))
    val confirmed = reclaimed.confirm(second, now + 601).toOption.get
    assertEquals(confirmed.remove(AccountIndexKind.Uid, second), Left(AccountRejection.IndexPermanent))
    assertEquals(AccountIndexEntry.register(Some(confirmed), first, now + 999999, None, AccountIndexKind.Uid), Left(AccountRejection.IndexConflict))
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
    assertEquals(next.hit("verify", Double.PositiveInfinity), Left(AccountRejection.Invalid))
  }

  test("乱造検知は verify 回数と相異なる端末数の両方で制限する") {
    assert(AbuseThresholds.verify(AuthRateCounts(10, 100, 5)))
    assert(!AbuseThresholds.verify(AuthRateCounts(11, 100, 5)))
    assert(!AbuseThresholds.verify(AuthRateCounts(1, 1, 6)))
    assert(!AbuseThresholds.verify(AuthRateCounts(6, 6, 2), strict = true))
    assert(!AbuseThresholds.registration(AuthRateCounts(1, 6, 0)))
    assert(!AbuseThresholds.login(AuthRateCounts(31, 31, 0)))
    assert(!AbuseThresholds.reset(AuthRateCounts(1, 4, 0)))
  }
}
