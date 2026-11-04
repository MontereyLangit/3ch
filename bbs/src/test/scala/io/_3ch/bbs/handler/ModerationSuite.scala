package io._3ch.bbs.handler

import munit.FunSuite
import io._3ch.bbs.external.cloudflare.WorkerEnv
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

/** `Moderation.parseRules` / `check` の現仕様固定（deny.cgi / ngwords.cgi の評価順と例外設定）。 */
class ModerationSuite extends FunSuite {
  import HandlerTestKit.*

  private def context(country: String = "JP", number: Int = 2, cap: js.UndefOr[Int] = js.undefined): js.Dynamic = {
    val gb = js.Dynamic.literal(
      form = js.Dynamic.literal(bbs = "sample", FROM = "name", mail = "", MESSAGE = "hello", subject = "title"),
      userInfo = "IP:192.0.2.1 AS1234-JP", country = country, settings = js.Dynamic.literal(), number = number
    )
    cap.foreach(c => gb.cap = c)
    gb
  }

  private def env(deny: String = "", ng: String = ""): WorkerEnv =
    js.Dynamic.literal(
      ch3www = js.Dynamic.literal(
        get = ((key: String) => js.Promise.resolve[js.Any](js.Dynamic.literal(
          text = (() => js.Promise.resolve[js.Any](if key == "deny.cgi" then deny else ng)): js.Function0[js.Promise[js.Any]]
        ))): js.Function1[String, js.Promise[js.Any]]
      )
    ).asInstanceOf[WorkerEnv]

  private def check(gb: js.Dynamic, e: WorkerEnv): Future[Int] = fut[Int](Moderation.check(gb, e))

  test("ルール検証: コメント、空欄、空白、不正な正規表現・動作") {
    assertEquals(Moderation.parseRules("# comment\n\n^sample$<>IP:192\\.0\\.2\\.1 <>word<>deny").length, 1)
    for (rule <- List("[<><><>deny", "bad format", "<><><>unknown")) {
      intercept[js.JavaScriptException](Moderation.parseRules(rule))
    }
  }

  test("アクセス拒否・非対象・Tor・スレ立てのみ") {
    val gb = context()
    for {
      denied <- check(gb, env("^sample$<>AS1234-<><>deny"))
      _ = assertEquals(denied, 0)
      _ = assert(gb.error.asInstanceOf[String].contains("アクセス規制"))
      elsewhere <- check(context(), env("^elsewhere$<><><>deny"))
      _ = assertEquals(elsewhere, 1)
      tor <- check(context(country = "T1"), env())
      _ = assertEquals(tor, 0)
      tateDame <- check(context(), env("<><><>TateDame"))
      _ = assertEquals(tateDame, 1)
      tateDameFirst <- check(context(number = 1), env("<><><>TateDame"))
      _ = assertEquals(tateDameFirst, 0)
    } yield ()
  }

  test("NGはタイトル・名前・メール・本文を検査する") {
    val fields = List("subject", "FROM", "mail", "MESSAGE")
    for {
      _ <- fields.foldLeft(Future.unit) { (acc, field) =>
        acc.flatMap { _ =>
          val gb = context()
          gb.form.updateDynamic(field)("禁止語")
          check(gb, env("", "<><>禁止語<>deny")).map(result => assertEquals(result, 0, field))
        }
      }
      encoded = context()
      _ = encoded.form.MESSAGE = "&#x66;orbidden"
      result <- check(encoded, env("", "<><>forbidden<>deny"))
      _ = assertEquals(result, 0)
    } yield ()
  }

  test("markの後も拒否ルールを評価し、既存の例外設定を維持する") {
    val gb = context()
    gb.settings.BBS_NO_MADAKANA = "checked"
    val pass = context()
    pass.settings.BBS_NGWORD_PASS = "checked"
    for {
      marked <- check(context(), env("<><><>mark\n<><><>deny"))
      _ = assertEquals(marked, 0)
      noMadakana <- check(gb, env("<><><>deny"))
      _ = assertEquals(noMadakana, 1)
      _ = assert(gb.form.MESSAGE.asInstanceOf[String].contains("Caution"))
      _ = assert(gb.form.FROM.asInstanceOf[String].contains("´・ω・｀"))
      capped <- check(context(cap = 1), env("<><><>deny"))
      _ = assertEquals(capped, 1)
      ngPass <- check(pass, env("", "<><>hello<>deny"))
      _ = assertEquals(ngPass, 1)
      invalid <- check(context(), env("invalid"))
      _ = assertEquals(invalid, 0)
    } yield ()
  }
}
