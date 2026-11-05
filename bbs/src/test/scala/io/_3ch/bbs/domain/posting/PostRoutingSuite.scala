package io._3ch.bbs.domain.posting

import munit.FunSuite
import io._3ch.common.domain.posting.{PostForm, PostKind}

/** 復号済みフォームの振り分けと必須項目の検査 */
class PostRoutingSuite extends FunSuite {
  private val base = PostForm(message = "本文", subject = "題名", bbs = "sample", time = "1")

  test("スレ立ては key を unixtime に、返信は題名を空にする") {
    assertEquals(PostRouting.route(base, "1700000000").map(f => (f.key, f.kind)), Right(("1700000000", PostKind.NewThread)))
    assertEquals(PostRouting.route(base.copy(key = "123"), "1700000000").map(f => (f.key, f.subject, f.kind)), Right(("123", "", PostKind.Reply)))
  }

  test("!newthread は 9 始まりの key でだけスレ立てにする") {
    val routed = PostRouting.route(base.copy(subject = "", key = "9123", message = "!newthread題名<br>本文"), "1700000000")
    assertEquals(routed.map(f => (f.subject, f.message, f.key)), Right(("題名", "<br>本文", "1700000000")))
    assertEquals(PostRouting.route(base.copy(subject = "", key = "8123", message = "!newthread題名"), "1").map(_.key), Right("8123"))
  }

  test("板名・key・time の不正な文字を除き、924 / 900 始まりの key は共通スレの板にする") {
    val routed = PostRouting.route(base.copy(bbs = "sa!mple", key = "900a1", time = "1x2"), "1")
    assertEquals(routed.map(f => (f.bbs, f.key, f.time)), Right(("924", "9001", "12")))
  }

  test("スレ立て本文先頭の !924 / !900 は共通スレのキーにし、残りが空なら本文なし") {
    assertEquals(PostRouting.route(base.copy(message = "!924 本文"), "1700000000").map(f => (f.bbs, f.key, f.message)), Right(("924", "9240000000", "本文")))
    assertEquals(PostRouting.route(base.copy(message = "!900<br>本文"), "1700000000").map(f => (f.bbs, f.key)), Right(("924", "9000000000")))
    assertEquals(PostRouting.route(base.copy(message = "!924"), "1700000000").left.map(_._1), Left(PostingError.EmptyMessage))
    assertEquals(PostRouting.route(base.copy(message = "!9240"), "1700000000").map(_.key), Right("1700000000"))
  }

  test("題名・名前・メール・submit の <br> は前後の空白ごと除く") {
    val routed = PostRouting.route(base.copy(subject = "a <br> b", name = "c<br>d", mail = "e <br>f", submit = "g<br> h"), "1")
    assertEquals(routed.map(f => (f.subject, f.name, f.mail, f.submit)), Right(("ab", "cd", "ef", "gh")))
  }

  test("必須項目の検査は 禁止板 → 本文 → 題名と key → time の順") {
    def error(form: PostForm): Option[PostingError] = PostRouting.route(form, "1").left.toOption.map(_._1)
    assertEquals(error(base.copy(bbs = "test", message = "")), Some(PostingError.ForbiddenBoard))
    assertEquals(error(base.copy(message = "", subject = "")), Some(PostingError.EmptyMessage))
    assertEquals(error(base.copy(subject = "", key = "")), Some(PostingError.MissingSubject("")))
    assertEquals(error(base.copy(time = "")), Some(PostingError.InvalidTime("")))
  }
}
