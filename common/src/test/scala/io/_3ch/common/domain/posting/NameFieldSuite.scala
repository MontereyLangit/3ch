package io._3ch.common.domain.posting

class NameFieldSuite extends munit.FunSuite {
  test("ユーザー ID の後に識別子を連結する") {
    assertEquals(NameField.withIdentity("名前", " </b>(@user)<b>", "JP.ab.cd ef-0123456789abcdef"),
      "名前 </b>(@user)<b> </b>(JP.ab.cd ef-0123456789abcdef)<b>")
    assertEquals(NameField.withIdentity("管理人 ★", "", "★"), "管理人 ★ </b>(★)<b>")
  }

  test("識別子非表示の空括弧を維持する") {
    assertEquals(NameField.withIdentity("名無しさん", "", ""), "名無しさん </b>()<b>")
    assertEquals(NameField.withIdentity("", "", ""), " </b>()<b>")
  }
}
