package io._3ch.bbs.handler

import io._3ch.bbs.durable.DurableTestKit
import io._3ch.bbs.durable.DurableTestKit.Env
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*

class PostingNumericSettingsSuite extends munit.FunSuite {
  import HandlerTestKit.*

  private def post(env: Env, subject: String, name: String, message: String, key: String = ""): Future[js.Dynamic] = {
    val fields = Seq("FROM" -> name, "mail" -> "", "MESSAGE" -> message, "subject" -> subject, "bbs" -> "sample", "key" -> key,
      "time" -> "1", "submit" -> "書き込む")
    val body = fields.map((name, value) => s"$name=${js.URIUtils.encodeURIComponent(value)}").mkString("&")
    val req = request("https://www.3ch.io/test/bbs.cgi", method = "POST", body = body, headers = Seq(
      "content-type" -> "application/x-www-form-urlencoded", "referer" -> "https://www.3ch.io/", "user-agent" -> "Mozilla/5.0",
      "CF-Connecting-IP" -> "192.0.2.1"
    ))
    PostingHandler.entry(req, env.worker).toFuture.map(_.asInstanceOf[js.Dynamic])
  }

  test("巨大な指数の文字数上限は Infinity として読み、スレ立てと返信を受け付ける") {
    withFixedTime(1700000000000d) {
      val env = DurableTestKit.newEnv()
      env.putR2("sample/SETTING.TXT", "BBS_SUBJECT_COUNT=1e2147483648\nBBS_NAME_COUNT=1e2147483648\nBBS_MESSAGE_COUNT=1e2147483648\n")
      for {
        created <- post(env, "題" * 100, "名" * 100, "本文")
        key = header(created, "x-threadkey").getOrElse(fail("スレ立てに失敗"))
        _ = assertEquals(header(created, "x-resnum"), Some("1"))
        replied <- post(env, "", "名" * 100, "文" * 5000, key)
      } yield assertEquals(header(replied, "x-resnum"), Some("2"))
    }
  }

  test("負のゼロと負数のアンダーフローは同じ識別子を生成する") {
    withFixedTime(1700000000000d) {
      def nameWith(reset: String): Future[String] = {
        val env = DurableTestKit.newEnv()
        env.putR2("sample/SETTING.TXT", s"BBS_ID_RESET=$reset\n")
        for {
          response <- post(env, "題名", "名前", "本文")
          key = header(response, "x-threadkey").getOrElse(fail("スレ立てに失敗"))
          dat <- env.thread(s"sample/$key").getDat().toFuture
        } yield dat.takeWhile(_ != '\n').split("<>", -1)(0)
      }
      for {
        negativeZero <- nameWith("-0")
        underflow <- nameWith("-1e-2147483649")
      } yield assertEquals(negativeZero, underflow)
    }
  }
}
