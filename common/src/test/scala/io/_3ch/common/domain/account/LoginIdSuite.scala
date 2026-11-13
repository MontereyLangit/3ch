package io._3ch.common.domain.account

import munit.FunSuite

class LoginIdSuite extends FunSuite {
  test("英数字とアンダースコアの ID を小文字化して保持する") {
    val id = LoginId.create("User_123", Set.empty, Set.empty).toOption.get
    assertEquals(id.value, "user_123")
    assertEquals(id, LoginId.parse("USER_123").toOption.get)
    assertEquals(id.hashCode(), LoginId.parse("user_123").toOption.get.hashCode())
    assertEquals(LoginId.parse("A_0").map(_.value), Right("a_0"))
    assertEquals(LoginId.parse("A" * 16).map(_.value), Right("a" * 16))
  }

  test("書式外の文字と境界外の長さを拒否し空白を取り除かない") {
    Seq("", "ab", "a" * 17, " user", "user ", "user\n", "user-name", "ユーザー", "ＵＳＥＲ", "İＤ", null)
      .foreach(raw => assertEquals(LoginId.parse(raw), Left(LoginIdRejection.Invalid), String.valueOf(raw)))
  }

  test("追加一覧が空でも固定の予約語は登録できる ID にしない") {
    Seq("admin", "root", "test", "3ch", "saku", "auth", "mod", "noname").foreach { raw =>
      assertEquals(LoginId.create(raw, Set.empty, Set.empty), Left(LoginIdRejection.Reserved))
      assert(LoginId.parse(raw).isRight)
    }
    assertEquals(LoginId.create("ADMIN", Set.empty, Set.empty), Left(LoginIdRejection.Reserved))
  }

  test("R2 の予約語と現在の板名を大文字小文字にかかわらず登録から除く") {
    assertEquals(LoginId.create("OPERATOR", Set("Operator"), Set.empty), Left(LoginIdRejection.Reserved))
    assertEquals(LoginId.create("NeWs", Set.empty, Set("NEWS")), Left(LoginIdRejection.Reserved))
    assertEquals(LoginId.create("news_user", Set("operator"), Set("news")).map(_.value), Right("news_user"))
  }
}
