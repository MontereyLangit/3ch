package io._3ch.common.domain.account

import munit.FunSuite

class PhoebeSuite extends FunSuite {
  private val now = 2000000000d
  private val hour = 3600d

  /** 一時間ごとに一回ずつ投稿したときの Lv の推移 */
  private def postHourly(kind: AccountKind, start: AccountLevel, hours: Int): AccountLevel =
    (1 to hours).foldLeft(start)((level, i) => level.afterPost(kind, now + i * hour))

  test("クライアント ID の T(lv) は 23 時間の 2 の lv-1 乗倍で N(lv) も同じ数") {
    assertEquals((1 to 9).map(Phoebe.hours(AccountKind.Client, _)).toList, (0 to 8).map(n => Option(23 << n)).toList)
    assertEquals(Phoebe.seconds(AccountKind.Client, 1), Some(23 * hour))
    assertEquals(Phoebe.posts(AccountKind.Client, 3), Some(92))
    assertEquals((3 to 9).flatMap(Phoebe.hours(AccountKind.Client, _)).sum, 11684)
  }

  test("ID・パスワードの T(lv) は 23 時間の max(1, lv-2) 倍") {
    assertEquals((1 to 9).map(Phoebe.hours(AccountKind.Password, _)).toList, List(23, 23, 23, 46, 69, 92, 115, 138, 161).map(Option(_)))
    assertEquals((3 to 9).flatMap(Phoebe.hours(AccountKind.Password, _)).sum, 644)
  }

  test("Google と Lv10 と範囲外は昇格しない") {
    assert((0 to 10).flatMap(Phoebe.hours(AccountKind.Google, _)).isEmpty)
    assertEquals(Phoebe.hours(AccountKind.Client, 10), None)
    assertEquals(Phoebe.hours(AccountKind.Password, 10), None)
    assertEquals(Phoebe.hours(AccountKind.Client, -1), None)
    val top = AccountLevel(10, now)
    assertEquals(top.afterPost(AccountKind.Password, now + 1e9).lv, 10)
    assertEquals(AccountLevel(10, now).afterPost(AccountKind.Google, now + 1e9), AccountLevel(10, now, 1))
  }

  test("経過時間と投稿数の両方が揃った投稿で一段だけ上がり数え直す") {
    val start = AccountLevel(1, now)
    val before = (1 to 22).foldLeft(start)((level, _) => level.afterPost(AccountKind.Password, now + 23 * hour))
    assertEquals(before, AccountLevel(1, now, 22))
    val raised = before.afterPost(AccountKind.Password, now + 23 * hour)
    assertEquals(raised, AccountLevel(2, now + 23 * hour, 0))
  }

  test("投稿数が揃っても経過時間が一秒足りなければ上がらない") {
    val many = AccountLevel(1, now, 1000)
    assertEquals(many.afterPost(AccountKind.Password, now + 23 * hour - 1).lv, 1)
    assertEquals(many.afterPost(AccountKind.Password, now + 23 * hour).lv, 2)
  }

  test("経過時間が十分でも投稿数が足りなければ上がらず二段以上は上がらない") {
    val idle = AccountLevel(1, now, 21)
    assertEquals(idle.afterPost(AccountKind.Client, now + 1e8), AccountLevel(1, now, 22))
    val ready = AccountLevel(3, now, 10000)
    assertEquals(ready.afterPost(AccountKind.Client, now + 1e8).lv, 4)
  }

  test("一時間に一回の投稿で ID・パスワードは 46 時間で Lv3、クライアント ID は 69 時間で Lv3") {
    assertEquals(postHourly(AccountKind.Password, AccountLevel(1, now), 45).lv, 2)
    assertEquals(postHourly(AccountKind.Password, AccountLevel(1, now), 46).lv, 3)
    assertEquals(postHourly(AccountKind.Client, AccountLevel(1, now), 68).lv, 2)
    assertEquals(postHourly(AccountKind.Client, AccountLevel(1, now), 69).lv, 3)
  }

  test("固定中は投稿を数えるが昇格しない") {
    val fixed = AccountLevel(1, now, 1000, Some(5))
    val after = fixed.afterPost(AccountKind.Password, now + 1e8)
    assertEquals(after, AccountLevel(1, now, 1001, Some(5)))
    assertEquals(after.effective, 5)
  }

  test("固定値は 0〜10 だけを受け付け None で外す") {
    val level = AccountLevel(3, now, 4)
    assertEquals(level.overridden(Some(0)).map(_.effective), Some(0))
    assertEquals(level.overridden(Some(10)).map(_.effective), Some(10))
    assertEquals(level.overridden(Some(11)), None)
    assertEquals(level.overridden(Some(-1)), None)
    assertEquals(level.copy(lvOverride = Some(7)).overridden(None), Some(level))
  }

  test("開始 Lv はクライアント ID と ID・パスワードが 1、メール認証済みが 3、Google が 10") {
    assertEquals(Phoebe.initial(AccountKind.Client, emailVerified = false), 1)
    assertEquals(Phoebe.initial(AccountKind.Password, emailVerified = false), 1)
    assertEquals(Phoebe.initial(AccountKind.Password, emailVerified = true), 3)
    assertEquals(Phoebe.initial(AccountKind.Google, emailVerified = false), 10)
  }

  test("BBS_PHOEBE は 0〜10 の整数だけを読み、それ以外は 0") {
    assertEquals(Seq(None, Some(""), Some("0"), Some("3"), Some(" 10 "), Some("11"), Some("-1"), Some("3.5"), Some("abc"), Some("007"), Some("１"))
      .map(Phoebe.required).toList, List(0, 0, 0, 3, 10, 0, 0, 0, 0, 0, 0))
  }

  test("必要 Lv 以上か免除の投稿者だけ書き込める") {
    assert(Phoebe.admits(3, 3, exempt = false))
    assert(!Phoebe.admits(2, 3, exempt = false))
    assert(Phoebe.admits(0, 10, exempt = true))
    assert(Phoebe.admits(0, 0, exempt = false))
  }
}
