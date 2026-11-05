package io._3ch.bbs.handler

import io._3ch.bbs.durable.DurableTestKit
import io._3ch.bbs.durable.DurableTestKit.Env
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*

class PostingOwnerCompatibilitySuite extends munit.FunSuite {
  import HandlerTestKit.*

  private val key = "1800000000"
  private val row = "名前<><>日付<> 本文 <>題名\n"
  private val brokenOwner = js.Dynamic.literal(ip = 42, shikibetsu = "", account = "")

  private def seeded(settings: String = "", dat: String = row): Future[Env] = {
    val env = DurableTestKit.newEnv()
    env.putCap("cap", "管理人")
    DurableTestKit.seed(env.threadInstance(s"sample/$key"), "dat" -> dat, "log" -> "", "settings" -> settings, "owner" -> brokenOwner).map(_ => env)
  }

  private def reply(env: Env, mail: String = ""): Future[js.Dynamic] = {
    val body = Seq("FROM" -> "", "mail" -> mail, "MESSAGE" -> "返信", "subject" -> "", "bbs" -> "sample", "key" -> key,
      "time" -> "9999999999", "submit" -> "書き込む").map((name, value) => s"$name=${js.URIUtils.encodeURIComponent(value)}").mkString("&")
    val req = request("https://www.3ch.io/test/bbs.cgi", headers = Seq(
      "content-type" -> "application/x-www-form-urlencoded", "referer" -> "https://www.3ch.io/", "user-agent" -> "Mozilla/5.0",
      "CF-Connecting-IP" -> "192.0.2.1"
    ), method = "POST", body = body)
    PostingHandler.entry(req, env.worker).toFuture.map(_.asInstanceOf[js.Dynamic])
  }

  test("キャップの返信は壊れた owner をデコードせず書き込む") {
    for {
      env <- seeded()
      res <- reply(env, "#!cap")
    } yield assertEquals(header(res, "x-resnum"), Some("2"))
  }

  test("閉鎖中の返信は壊れた owner のデコードより先に拒否する") {
    for {
      env <- seeded(settings = "BBS_HEISA=checked<>")
      res <- reply(env)
      body <- text(res)
    } yield {
      assertEquals(header(res, "x-resnum"), None)
      assert(body.contains(PostingText.message(io._3ch.bbs.domain.posting.PostingError.Closed)))
    }
  }

  test("満杯の返信は壊れた owner のデコードより先に拒否する") {
    for {
      env <- seeded(dat = row * 999)
      res <- reply(env)
      body <- text(res)
    } yield {
      assertEquals(header(res, "x-resnum"), None)
      assert(body.contains(PostingText.message(io._3ch.bbs.domain.posting.PostingError.ThreadFull)))
    }
  }
}
