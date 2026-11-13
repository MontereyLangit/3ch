package io._3ch.bbs.repository

import io._3ch.bbs.domain.posting.moderation.{ModerationRuleFile, ModerationRuleSource}
import io._3ch.bbs.external.cloudflare.WorkerEnv
import munit.FunSuite

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

class ModerationRuleRepositorySuite extends FunSuite {
  private def env(get: String => js.Promise[js.Any]): WorkerEnv =
    js.Dynamic.literal(ch3www = js.Dynamic.literal(get = get: js.Function1[String, js.Promise[js.Any]])).asInstanceOf[WorkerEnv]

  private def stored(text: js.Function0[js.Promise[js.Any]]): js.Promise[js.Any] = js.Promise.resolve[js.Any](js.Dynamic.literal(text = text))

  test("ファイル名どおりに読み、無ければ Missing") {
    val requested = js.Array[String]()
    val e = env { key => requested.push(key); js.Promise.resolve[js.Any](null) }
    for {
      access <- ModerationRuleRepository.load(ModerationRuleFile.Access, e)
      ng <- ModerationRuleRepository.load(ModerationRuleFile.NgWords, e)
    } yield {
      assertEquals((access, ng), (ModerationRuleSource.Missing, ModerationRuleSource.Missing))
      assertEquals(requested.toList, List("deny.cgi", "ngwords.cgi"))
    }
  }

  test("書式が正しければ Loaded、不正か本文が読めなければ Broken") {
    val valid = env(_ => stored(() => js.Promise.resolve[js.Any]("<><><>deny")))
    val invalid = env(_ => stored(() => js.Promise.resolve[js.Any]("invalid")))
    val unreadable = env(_ => stored(() => js.Promise.reject("text failed")))
    for {
      loaded <- ModerationRuleRepository.load(ModerationRuleFile.Access, valid)
      broken <- ModerationRuleRepository.load(ModerationRuleFile.Access, invalid)
      failed <- ModerationRuleRepository.load(ModerationRuleFile.Access, unreadable)
    } yield {
      assert(loaded match { case ModerationRuleSource.Loaded(rules) => rules.rules.length == 1; case _ => false })
      assertEquals((broken, failed), (ModerationRuleSource.Broken, ModerationRuleSource.Broken))
    }
  }

  test("R2 の get の失敗はそのまま失敗にする") {
    ModerationRuleRepository.load(ModerationRuleFile.Access, env(_ => js.Promise.reject("get failed"))).failed.map { error =>
      assert(error.toString.contains("get failed"), error.toString)
    }
  }
}
