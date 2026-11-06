package io._3ch.common.domain.account

import munit.FunSuite

class PasswordSuite extends FunSuite {
  private val id = LoginId.parse("alice").toOption.get

  test("ASCII の UTF-8 長は8〜128バイトを受け付ける") {
    assertEquals(Password.create("a" * 7, id), Left(PasswordRejection.Length))
    assertEquals(Password.create("a" * 8, id).map(_.value), Right("a" * 8))
    assertEquals(Password.create("a" * 128, id).map(_.value), Right("a" * 128))
    assertEquals(Password.create("a" * 129, id), Left(PasswordRejection.Length))
  }

  test("日本語と補助文字は文字数ではなく UTF-8 バイト数で数える") {
    assertEquals(Password.create("ああ", id), Left(PasswordRejection.Length))
    assert(Password.create("あああ", id).isRight)
    assert(Password.create("あ" * 42 + "ab", id).isRight)
    assertEquals(Password.create("あ" * 43, id), Left(PasswordRejection.Length))
    assert(Password.create("😀" * 2, id).isRight)
    assert(Password.create("😀" * 32, id).isRight)
    assertEquals(Password.create("😀" * 33, id), Left(PasswordRejection.Length))
  }

  test("NFC 後に長さを検証し正準等価な文字列は同じパスワードにする") {
    assertEquals(Password.create("e\u0301" * 3, id), Left(PasswordRejection.Length))
    val decomposed = Password.create("e\u0301" * 4, id).toOption.get
    val composed = Password.create("é" * 4, id).toOption.get
    assertEquals(decomposed.value, "é" * 4)
    assertEquals(decomposed, composed)
    assertEquals(decomposed.hashCode(), composed.hashCode())
    assert(Password.create("e\u0301" * 64, id).isRight)
    assertEquals(Password.create("e\u0301" * 65, id), Left(PasswordRejection.Length))
  }

  test("前後の空白と大文字小文字を保持し文字種は強制しない") {
    assertEquals(Password.create(" AbCd12 ", id).map(_.value), Right(" AbCd12 "))
    assertEquals(Password.create(" " * 8, id).map(_.value), Right(" " * 8))
    assertEquals(Password.create("\tAbCd12\n", id).map(_.value), Right("\tAbCd12\n"))
  }

  test("ユーザー ID の包含は ASCII の大文字小文字を区別せず拒否する") {
    Seq("alice123", "123ALICE", "xxAlIcExx").foreach { raw =>
      assertEquals(Password.create(raw, id), Left(PasswordRejection.ContainsLoginId))
    }
    val longId = LoginId.parse("user1234").toOption.get
    assertEquals(Password.create("USER1234", longId), Left(PasswordRejection.ContainsLoginId))
    assertEquals(Password.create("AL1CE123", id).map(_.value), Right("AL1CE123"))
    val asciiId = LoginId.parse("abc").toOption.get
    assertEquals(Password.create("éabc1234", asciiId), Left(PasswordRejection.ContainsLoginId))
  }

  test("孤立サロゲートは UTF-8 への置換に頼らず拒否する") {
    Seq("abcdefgh\ud800", "abcdefgh\udfff", "\ud800a\udc00abcdefgh", "abcdefgh\ud800\ud800", null).foreach { raw =>
      assertEquals(Password.create(raw, id), Left(PasswordRejection.InvalidUnicode))
    }
    assert(Password.create("abcdefgh\ud800\udc00", id).isRight)
  }

  test("診断用の文字列に平文パスワードを含めない") {
    val password = Password.create("do-not-log-this", id).toOption.get
    assert(!password.toString.contains(password.value))
  }
}
