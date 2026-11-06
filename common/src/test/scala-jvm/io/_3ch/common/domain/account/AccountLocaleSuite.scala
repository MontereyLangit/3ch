package io._3ch.common.domain.account

import java.util.{Locale, TimeZone}
import munit.FunSuite

class AccountLocaleSuite extends FunSuite {
  test("トルコ語の既定ロケールでも ID とパスワードの ASCII 小文字化は変わらない") {
    val previous = Locale.getDefault
    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"))
      val id = LoginId.create("Iris", Set.empty, Set.empty).toOption.get
      assertEquals(id.value, "iris")
      assertEquals(LoginId.create("ADMIN", Set.empty, Set.empty), Left(LoginIdRejection.Reserved))
      assertEquals(Password.create("IRIS1234", id), Left(PasswordRejection.ContainsLoginId))
      assertEquals(Password.create("e\u0301" * 4, id).map(_.value), Right("é" * 4))
    } finally {
      Locale.setDefault(previous)
    }
  }

  test("計数の窓とロックはタイムゾーンによらず UNIX 秒で判定する") {
    val previous = TimeZone.getDefault
    val ip = "a" * 64
    val now = 2000000000d
    val state = LoginThrottle(Vector(LoginFailure(ip, now)), Map(ip -> (now + 3600)))
    try {
      val results = Seq("UTC", "Asia/Tokyo", "America/New_York").map { zone =>
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        state.check(ip, now + 3599) -> state.check(ip, now + 3600)
      }
      assert(results.forall(_ == results.head))
      assertEquals(results.head._1.map(_.locked), Right(true))
      assertEquals(results.head._2.map(_.locked), Right(false))
    } finally {
      TimeZone.setDefault(previous)
    }
  }
}
