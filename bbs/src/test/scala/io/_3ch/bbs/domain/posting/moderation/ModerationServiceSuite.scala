package io._3ch.bbs.domain.posting.moderation

import io._3ch.bbs.domain.posting.ModerationDenial
import io._3ch.common.domain.BbsSettings
import io._3ch.common.domain.posting.{PostForm, UserInfo}
import munit.FunSuite

import scala.collection.immutable.ListMap
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class ModerationServiceSuite extends FunSuite {
  import ModerationDecision.{Deny, Pass}

  private val form = PostForm(name = "name", message = "hello", subject = "title", bbs = "sample")
  private val userInfo = UserInfo("", "192.0.2.1", "1234", "JP", "Tokyo", "", "", "")
  private val mark = "</b>[´・ω・｀]<b> "
  private val caution = " <small>👀 Madakana: Caution</small>"

  private def input(form: PostForm = form, cap: Boolean = false, country: String = "JP", number: Double = 2, settings: (String, String)*): ModerationInput =
    ModerationInput(form, userInfo, cap, country, number, BbsSettings(ListMap.from(settings)))

  private def source(text: String): ModerationRuleSource =
    ModerationRuleSet.parse(text).fold(_ => ModerationRuleSource.Broken, ModerationRuleSource.Loaded(_))

  /** 読んだファイルを記録するローダー。空文字のファイルは無いものとして扱う */
  private final class Loader(sources: Map[ModerationRuleFile, ModerationRuleSource]) {
    def this(access: String = "", ng: String = "") = this(Map(ModerationRuleFile.Access -> access, ModerationRuleFile.NgWords -> ng).collect {
      case (file, text) if text.nonEmpty => file -> source(text)
    })
    val loaded = scala.collection.mutable.ListBuffer.empty[ModerationRuleFile]
    def apply(file: ModerationRuleFile): Future[ModerationRuleSource] = {
      loaded += file
      Future.successful(sources.getOrElse(file, ModerationRuleSource.Missing))
    }
  }

  private def check(in: ModerationInput, access: String = "", ng: String = ""): Future[ModerationDecision] = {
    val loader = new Loader(access, ng)
    ModerationService.check(in, loader.apply)
  }

  test("投稿者情報の拒否・板が違えば対象外・Tor・スレ立てのみの禁止") {
    for {
      denied <- check(input(), "^sample$<>AS1234-<><>deny")
      _ = assertEquals(denied, Deny(ModerationDenial.Host, form))
      elsewhere <- check(input(), "^elsewhere$<><><>deny")
      _ = assertEquals(elsewhere, Pass(form))
      tor <- check(input(country = "T1"), "<><><>mark")
      _ = assertEquals(tor, Deny(ModerationDenial.Tor, form))
      tateDame <- check(input(), "<><><>TateDame")
      _ = assertEquals(tateDame, Pass(form))
      tateDameFirst <- check(input(number = 1), "<><><>TateDame")
      _ = assertEquals(tateDameFirst, Deny(ModerationDenial.TateDame, form))
    } yield ()
  }

  test("キャップと saku 板は対象外で、ルールファイルを読まない") {
    val capLoader = new Loader("<><><>deny")
    val sakuLoader = new Loader("<><><>deny")
    for {
      capped <- ModerationService.check(input(cap = true), capLoader.apply)
      _ = assertEquals(capped, Pass(form))
      saku <- ModerationService.check(input(form = form.copy(bbs = "saku")), sakuLoader.apply)
      _ = assertEquals(saku, Pass(form.copy(bbs = "saku")))
      _ = assertEquals((capLoader.loaded.toList, sakuLoader.loaded.toList), (Nil, Nil))
    } yield ()
  }

  test("NG はタイトル・名前・メール・本文を検査し、文字参照を復元した内容も照合する") {
    val fields = List[PostForm => PostForm](_.copy(subject = "禁止語"), _.copy(name = "禁止語"), _.copy(mail = "禁止語"), _.copy(message = "禁止語"))
    for {
      results <- Future.traverse(fields)(f => check(input(form = f(form)), ng = "<><>禁止語<>deny"))
      _ = results.foreach(r => assert(r.isInstanceOf[Deny], r))
      encoded <- check(input(form = form.copy(message = "&#x66;orbidden")), ng = "<><>forbidden<>deny")
      _ = assertEquals(encoded, Deny(ModerationDenial.NgWord, form.copy(message = "&#x66;orbidden")))
      raw <- check(input(form = form.copy(message = "&#x66;orbidden")), ng = "<><>&#x66;<>deny")
      _ = assert(raw.isInstanceOf[Deny])
    } yield ()
  }

  test("mark の後も拒否ルールを評価し、BBS_NO_MADAKANA と BBS_NGWORD_PASS を維持する") {
    for {
      marked <- check(input(), "<><><>mark\n<><><>deny")
      _ = assertEquals(marked, Deny(ModerationDenial.Host, form.copy(name = mark + "name", message = "hello" + caution)))
      noMadakana <- check(input(settings = "BBS_NO_MADAKANA" -> "checked"), "<><><>deny")
      _ = assertEquals(noMadakana, Pass(form.copy(name = mark + "name", message = "hello" + caution)))
      notChecked <- check(input(settings = "BBS_NO_MADAKANA" -> "checked "), "<><><>deny")
      _ = assert(notChecked.isInstanceOf[Deny])
      ngPass <- check(input(settings = "BBS_NGWORD_PASS" -> "checked"), ng = "<><>hello<>deny")
      _ = assertEquals(ngPass, Pass(form))
      hostNotPassed <- check(input(settings = "BBS_NGWORD_PASS" -> "checked"), "<>AS1234<><>deny")
      _ = assert(hostNotPassed.isInstanceOf[Deny])
    } yield ()
  }

  test("壊れたルールファイルは拒否") {
    for {
      invalid <- check(input(), "invalid")
      _ = assertEquals(invalid, Deny(ModerationDenial.BrokenRules, form))
      unreadable <- ModerationService.check(input(), new Loader(Map(ModerationRuleFile.Access -> ModerationRuleSource.Broken)).apply)
      _ = assertEquals(unreadable, Deny(ModerationDenial.BrokenRules, form))
    } yield ()
  }

  test("mark が重なると名前の印は 1 回だけ、本文の注意書きはルールごとに付く") {
    for {
      twice <- check(input(), "<><><>mark\n<><><>mark")
      _ = assertEquals(twice, Pass(form.copy(name = mark + "name", message = "hello" + caution + caution)))
      word <- check(input(), ng = "<><>hello<>mark")
      _ = assertEquals(word, Pass(form.copy(message = "hello" + caution)))
    } yield ()
  }

  test("拒否で評価をやめ、それまでの mark は残り、後のファイルは読まない") {
    val loader = new Loader("<><><>mark\n<><><>deny\n<><><>mark", "<><><>mark")
    for {
      markThenDeny <- ModerationService.check(input(), loader.apply)
      _ = assertEquals(markThenDeny, Deny(ModerationDenial.Host, form.copy(name = mark + "name", message = "hello" + caution)))
      _ = assertEquals(loader.loaded.toList, List(ModerationRuleFile.Access))
      denyThenMark <- check(input(), "<><><>deny\n<><><>mark")
      _ = assertEquals(denyThenMark, Deny(ModerationDenial.Host, form))
      acrossFiles <- check(input(), "<><><>mark", "<><>hello<>deny")
      _ = assertEquals(acrossFiles, Deny(ModerationDenial.NgWord, form.copy(name = mark + "name", message = "hello" + caution)))
      broken <- check(input(), "<><><>mark", "invalid")
      _ = assertEquals(broken, Deny(ModerationDenial.BrokenRules, form.copy(name = mark + "name", message = "hello" + caution)))
    } yield ()
  }

  test("ルールの照合には mark を付ける前の内容を使う") {
    check(input(), "<><><>mark", "<><>Caution<>deny").map { result =>
      assertEquals(result, Pass(form.copy(name = mark + "name", message = "hello" + caution)))
    }
  }

  test("対象欄は投稿者情報の文字列と照合する") {
    for {
      uid <- check(input(), "<>^UID: IP:192\\.0\\.2\\.1 AS1234-JP,Tokyo,Device<><>deny")
      _ = assert(uid.isInstanceOf[Deny])
    } yield ()
  }

  test("文字参照の復元は数値参照を先に、範囲外はそのまま残す") {
    assertEquals(ModerationService.decodeEntities("&#65;&#X42;&#x1F440;&amp;lt;&lt;&apos;&quot;&gt;"), "AB👀&lt;<'\">")
    assertEquals(ModerationService.decodeEntities("&#0;&#x110000;&#99999999999999999999;&#xd800;&nbsp;"), "&#0;&#x110000;&#99999999999999999999;\ud800&nbsp;")
    assertEquals(ModerationService.decodeEntities("&#38;lt;"), "<")
  }
}
