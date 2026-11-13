package io._3ch.common.domain.account

import munit.FunSuite

class AbuseReviewSuite extends FunSuite {
  private val now = 2000000000d
  private val key = "a" * 64

  test("認証の計数は 1 時間 10 回・24 時間 5 端末を超えたら理由を返し、strict なら半分で超える") {
    assertEquals(AbuseReason.ofVerify(AuthRateCounts(10, 10, 5), strict = false), Vector.empty)
    assertEquals(AbuseReason.ofVerify(AuthRateCounts(11, 11, 6), strict = false), Vector(AbuseReason.VerifyBurst, AbuseReason.DeviceSpread))
    assertEquals(AbuseReason.ofVerify(AuthRateCounts(6, 6, 2), strict = true), Vector(AbuseReason.VerifyBurst))
    assertEquals(AbuseReason.ofVerify(AuthRateCounts(5, 5, 3), strict = true), Vector(AbuseReason.DeviceSpread))
    assert(!AbuseThresholds.registration(AuthRateCounts(3, 3, 0), strict = true))
    assert(AbuseThresholds.registration(AuthRateCounts(5, 5, 0)))
  }

  test("同じ接続元と理由は 24 時間以内なら一件にまとめ、新しい順に上限まで残す") {
    val first = AbuseReview().flag(key, AbuseReason.VerifyBurst, AuthRateCounts(11, 11, 1), now).toOption.get
    val again = first.flag(key, AbuseReason.VerifyBurst, AuthRateCounts(12, 12, 1), now + 60).toOption.get
    assertEquals(again.flags, Vector(AbuseFlag(key, AbuseReason.VerifyBurst, now, now + 60, 2, 12, 12, 1)))
    val other = again.flag(key, AbuseReason.DeviceSpread, AuthRateCounts(3, 3, 6), now + 61).toOption.get
    assertEquals(other.flags.map(_.reason), Vector(AbuseReason.DeviceSpread, AbuseReason.VerifyBurst))
    val nextDay = again.flag(key, AbuseReason.VerifyBurst, AuthRateCounts(11, 11, 1), now + 60 + 86400).toOption.get
    assertEquals(nextDay.flags.map(_.count), Vector(1, 2))
    val full = (0 until AbuseReview.limit + 1).foldLeft(AbuseReview()) { (review, i) =>
      review.flag(f"$i%064x", AbuseReason.RegisterBurst, AuthRateCounts(6, 6, 0), now + i).toOption.get
    }
    assertEquals(full.flags.size, AbuseReview.limit)
    assertEquals(full.flags.last.firstSeen, now + 1)
    assertEquals(AbuseReview().flag("short", AbuseReason.VerifyBurst, AuthRateCounts(1, 1, 0), now), Left(AccountRejection.Invalid))
  }
}
