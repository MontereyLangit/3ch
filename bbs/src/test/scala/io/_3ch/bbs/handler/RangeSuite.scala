package io._3ch.bbs.handler

import munit.FunSuite
import io._3ch.bbs.durable.DurableTestKit
import io._3ch.bbs.durable.DurableTestKit.Env
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

/**
 * v6 の現仕様固定: dat ルート経由で観測できる Range / If-Modified-Since の挙動（旧 respondPlainText の特性テストの移植）。
 * KB-nn は docs/既知不具合台帳.md を参照。
 * UTF-8 の Range 応答 (KB-20)・respond500・KB-22(生タグ入り404本文: URLが%エンコードされ到達不能)は公開ルートから観測できないため対象外。
 */
class RangeSuite extends FunSuite {
  import HandlerTestKit.*

  private val Key = "1800000000"

  private final class Dat(env: Env, val full: List[Int]) {
    def total: Int = full.length
    def fetchDat(headers: (String, String)*): Future[js.Dynamic] =
      fut[js.Dynamic](PublicRoutes.fetch(request(s"http://x/board1/dat/$Key.dat", headers), env.worker, ctx))
  }

  private def datWith(message: String): Future[Dat] = {
    val env = DurableTestKit.newEnv()
    val t = env.thread(s"board1/$Key")
    for {
      _ <- t.addRes(s"name<><>2026/09/22(火) 12:00:00<>$message<>title", "1<>log",
        js.Dynamic.literal(bbs = "board1", key = Key, title = "title", resNum = 1, sage = false, maxThreads = 700, create = true)).toFuture
      res <- fut[js.Dynamic](PublicRoutes.fetch(request(s"http://x/board1/dat/$Key.dat"), env.worker, ctx))
      full <- bytes(res)
    } yield new Dat(env, full)
  }

  test("dat Range: end が総バイト数を超える場合は総バイト数-1に切り詰められる") {
    for {
      d <- datWith("hello")
      res <- d.fetchDat("range" -> "bytes=5-100000")
    } yield {
      assertEquals(status(res), 206)
      assertEquals(header(res, "content-range"), Some(s"bytes 5-${d.total - 1}/${d.total}"))
    }
  }

  test("KB-21: dat Range: サフィックス形式(bytes=-N)は「末尾Nバイト」ではなくstart=0として解釈される（HTTP仕様との相違）") {
    for {
      d <- datWith("hello")
      res <- d.fetchDat("range" -> "bytes=-5")
      body <- bytes(res)
    } yield {
      assertEquals(status(res), 206)
      assertEquals(header(res, "content-range"), Some(s"bytes 0-5/${d.total}"))
      assertEquals(body, d.full.take(6))
    }
  }

  test("dat Range: start>end は416でContent-Range: bytes */{total}") {
    for {
      d <- datWith("hello")
      res <- d.fetchDat("range" -> "bytes=5-2")
      body <- text(res)
    } yield {
      assertEquals(status(res), 416)
      assertEquals(header(res, "content-range"), Some(s"bytes */${d.total}"))
      assertEquals(body, "Requested Range Not Satisfiable")
    }
  }

  test("dat Range: start==total も416") {
    for {
      d <- datWith("hello")
      res <- d.fetchDat("range" -> s"bytes=${d.total}-")
    } yield {
      assertEquals(status(res), 416)
      assertEquals(header(res, "content-range"), Some(s"bytes */${d.total}"))
    }
  }

  test("dat Range: Shift_JISのマルチバイト境界を無頓着に切る（「火」の2バイト目だけを返す）") {
    for {
      d <- datWith("hello")
      at = d.full.indexOf(0x89) // 「火」= 0x89 0xCE
      res <- d.fetchDat("range" -> s"bytes=${at + 1}-${at + 1}")
      body <- bytes(res)
    } yield {
      assertEquals(status(res), 206)
      assertEquals(body, List(0xce))
    }
  }

  test("dat: 通常応答はAccept-RangesとLast-Modifiedを持つ") {
    for {
      d <- datWith("hello")
      res <- d.fetchDat()
    } yield {
      assertEquals(status(res), 200)
      assertEquals(header(res, "accept-ranges"), Some("bytes"))
      assert(header(res, "last-modified").exists(_.nonEmpty))
    }
  }

  test("dat: If-Modified-SinceがLast-Modifiedより未来なら304・ボディなし。過去なら200") {
    for {
      d <- datWith("hello")
      future <- d.fetchDat("if-modified-since" -> new js.Date(js.Date.now() + 86400000).toUTCString())
      futureBody <- bytes(future)
      past <- d.fetchDat("if-modified-since" -> new js.Date("2000-01-01T00:00:00Z").toUTCString())
    } yield {
      assertEquals(status(future), 304)
      assertEquals(futureBody.length, 0)
      assert(header(future, "last-modified").exists(_.nonEmpty))
      assertEquals(status(past), 200)
    }
  }
}
