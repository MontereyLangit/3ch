package io._3ch.bbs.domain.posting

import io._3ch.common.domain.BbsSettings
import io._3ch.common.domain.posting.PostForm

import scala.collection.immutable.ListMap

class PostFormLimitsSuite extends munit.FunSuite {
  private def settings(entries: (String, String)*) = BbsSettings(ListMap(entries*))
  private val form = PostForm(name = "名前", mail = "", message = "本文", subject = "")

  test("題名・名前・メールの順に検査し、文言の上限は設定値の文字列そのもの") {
    val long = form.copy(subject = "s" * 65, name = "n" * 65)
    assertEquals(PostFormLimits.check(long, settings(), 2, strongCap = false), Left(PostingError.FieldTooLong(PostField.Subject, 65, "64")))
    assertEquals(PostFormLimits.check(form.copy(mail = "abcd"), settings("BBS_MAIL_COUNT" -> "3.0"), 2, strongCap = false),
      Left(PostingError.FieldTooLong(PostField.Mail, 4, "3.0")))
  }

  test("スレ立ての本文は設定が 65536 未満なら 65536 文字まで、返信は設定の上限") {
    assertEquals(PostFormLimits.check(form.copy(message = "x" * 65536), settings(), 1, strongCap = false).map(_.message.length), Right(65536))
    assertEquals(PostFormLimits.check(form.copy(message = "x" * 65537), settings(), 1, strongCap = false), Left(PostingError.FieldTooLong(PostField.Message, 65537, "65536")))
    assertEquals(PostFormLimits.check(form.copy(message = "x" * 11), settings("BBS_MESSAGE_COUNT" -> "10"), 2, strongCap = false),
      Left(PostingError.FieldTooLong(PostField.Message, 11, "10")))
  }

  test("行数は返信だけ BBS_LINE_NUMBER の 2 倍まで") {
    val lines = form.copy(message = Seq.fill(5)("l").mkString("<br>"))
    assertEquals(PostFormLimits.check(lines, settings("BBS_LINE_NUMBER" -> "2"), 2, strongCap = false), Left(PostingError.TooManyLines(5, "4")))
    assert(PostFormLimits.check(lines, settings("BBS_LINE_NUMBER" -> "2"), 1, strongCap = false).isRight)
  }

  test("名前が空なら BBS_NONAME_NAME、無ければ既定の名無し") {
    assertEquals(PostFormLimits.check(form.copy(name = ""), settings("BBS_NONAME_NAME" -> "板の名無し"), 2, strongCap = false).map(_.name), Right("板の名無し"))
    assertEquals(PostFormLimits.check(form.copy(name = ""), settings(), 2, strongCap = false).map(_.name), Right("名無しさん＠３ちゃんねる"))
  }

  test("強キャップは検査も名前の補完もしない。!ADMIN: は本文の検査と名前の補完だけしない") {
    val empty = form.copy(name = "", subject = "s" * 100)
    assertEquals(PostFormLimits.check(empty, settings(), 2, strongCap = true), Right(empty))
    val admin = form.copy(name = "", message = "!ADMIN:" + "x" * 5000)
    assertEquals(PostFormLimits.check(admin, settings(), 2, strongCap = false), Right(admin))
    assert(PostFormLimits.check(admin.copy(name = "n" * 65), settings(), 2, strongCap = false).isLeft)
  }
}
