package io._3ch.bbs.durable

import munit.FunSuite
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*

class ThreadPostRateSuite extends FunSuite {
  import DurableTestKit.{newEnv, rejectionMessage, seed}

  private val key = "2000000001"
  private val first = "n<><>d<> 最初の本文 <>t\n"
  private def rate(level: String = "1", threadCount: Int = 40): js.Dynamic = js.Dynamic.literal(
    level = level, ip = "ip-a", shiki = "shiki-a", aid = "", timecount = 18, timeclose = 10, samba24 = 0, fr = 0,
    threadRentou = threadCount, threadRentouReset = 3600, keys = "ip,shiki,aid")
  private def creation: js.Dynamic = js.Dynamic.literal(
    ip = "ip-a", userAgent = "ua-a", networkGroup = "net-a", title = "t", tatesugi = 1, tatesugiResetSeconds = 3600, junban = 0, junbanResetSeconds = 3600)
  private def posting(level: String = "1", create: Boolean = false, threadCount: Int = 40): js.Dynamic =
    js.Dynamic.literal(bbs = "board", key = key, title = "t", create = create, creationLimit = creation, rateLimit = rate(level, threadCount))

  test("off は RPC に材料があっても判定も履歴保存もしない") {
    val env = newEnv()
    val instance = env.threadInstance(s"board/$key")
    val recent = js.Array(js.Dynamic.literal(t = js.Date.now() / 1000, ip = "ip-a", shiki = "shiki-a", aid = ""))
    for {
      _ <- seed(instance, "dat" -> first, "log" -> "1<>log\n", "recentPosts" -> recent)
      result <- instance.addRes("n<><>d<> 最初の本文 <>", "2<>log", posting(level = "off", threadCount = 1)).toFuture
      _ = assertEquals((result.count, result.swallowed, result.observed.toList), (2, false, Nil))
      stored <- instance.state.storage.get[js.Array[js.Dynamic]]("recentPosts").toFuture
      _ = assertEquals(js.JSON.stringify(stored.get), js.JSON.stringify(recent))
      meta <- env.boardInstance("board").state.storage.get[js.Any]("postRateMeta").toFuture
      _ = assert(meta.isEmpty)
      dat <- instance.getDat().toFuture
      _ = assertEquals(dat, first + "n<><>d<> 最初の本文 <>\n")
    } yield ()
  }

  test("スレへの同時投稿は先行した投稿の履歴を見て後の投稿を拒否する") {
    val env = newEnv()
    val thread = env.thread(s"board/$key")
    for {
      _ <- thread.setDat(first).toFuture
      results <- Future.sequence(Seq("二番目", "三番目").map { body =>
        thread.addRes(s"n<><>d<> $body <>", "2<>log", posting(threadCount = 1)).toFuture
          .map[Either[String, Int]](result => Right(result.count)).recover { case error => Left(DurableTestKit.messageOf(error)) }
      })
      _ = assertEquals(results.count(_ == Right(2)), 1)
      _ = assertEquals(results.count(_ == Left("post-rate:thread:rentou")), 1)
      page <- env.boardInstance("board").state.storage.get[js.Array[js.Dynamic]]("postRateHistory:0").toFuture
      _ = assertEquals(page.get.toList.map(_.status.asInstanceOf[String]), List("completed"))
    } yield ()
  }

  test("一括保存の失敗はスレ立てと連投の両予約を取り消す") {
    val env = newEnv()
    val instance = env.threadInstance(s"board/$key")
    val storage = instance.state.storage.asInstanceOf[js.Dynamic]
    val original = storage.put.asInstanceOf[js.Function2[js.Any, js.UndefOr[js.Any], js.Promise[js.Any]]]
    storage.put = ((fields: js.Any, value: js.UndefOr[js.Any]) => {
      if (js.isUndefined(fields.asInstanceOf[js.Dynamic].dat)) original(fields, value)
      else js.Promise.reject(new js.Error("write failed"))
    }): js.Function2[js.Any, js.UndefOr[js.Any], js.Promise[js.Any]]
    for {
      message <- rejectionMessage(instance.addRes("n<><>d<> 本文 <>t", "1<>log", posting(create = true)).toFuture)
      _ = assertEquals(message, "write failed")
      page <- env.boardInstance("board").state.storage.get[js.Array[js.Dynamic]]("postRateHistory:0").toFuture
      _ = assertEquals(page.get.length, 0)
      allowed <- env.board("board").reserveThreadCreation("2000000002", creation).toFuture
      _ = assertEquals(allowed, "")
      dat <- instance.state.storage.get[js.Any]("dat").toFuture
      recent <- instance.state.storage.get[js.Any]("recentPosts").toFuture
      _ = assert(dat.isEmpty && recent.isEmpty)
    } yield ()
  }

  test("板の連投予約の応答を失って拒否した投稿は両予約を取り消す") {
    val env = newEnv()
    val board = env.boardInstance("board").asInstanceOf[js.Dynamic]
    val original = board.reservePost
    board.reservePost = ((id: String, identity: js.Dynamic, settings: js.Dynamic) => {
      original.call(board, id, identity, settings).asInstanceOf[js.Promise[String]].toFuture
        .flatMap(_ => Future.failed[String](js.JavaScriptException(new js.Error("lost response")))).toJSPromise
    }): js.Function3[String, js.Dynamic, js.Dynamic, js.Promise[String]]
    for {
      message <- rejectionMessage(env.thread(s"board/$key").addRes("n<><>d<> 本文 <>t", "1<>log", posting(create = true)).toFuture)
      _ = assertEquals(message, "lost response")
      page <- env.boardInstance("board").state.storage.get[js.Array[js.Dynamic]]("postRateHistory:0").toFuture
      _ = assertEquals(page.get.length, 0)
      allowed <- env.board("board").reserveThreadCreation("2000000002", creation).toFuture
      _ = assertEquals(allowed, "")
      dat <- env.thread(s"board/$key").getDat().toFuture
      _ = assertEquals(dat, "")
    } yield ()
  }

  test("スレ立て予約の応答を失って拒否した投稿は予約を取り消す") {
    val env = newEnv()
    val board = env.boardInstance("board").asInstanceOf[js.Dynamic]
    val original = board.reserveThreadCreation
    board.reserveThreadCreation = ((id: String, limit: js.Dynamic) => {
      original.call(board, id, limit).asInstanceOf[js.Promise[String]].toFuture
        .flatMap(_ => Future.failed[String](js.JavaScriptException(new js.Error("lost response")))).toJSPromise
    }): js.Function2[String, js.Dynamic, js.Promise[String]]
    for {
      message <- rejectionMessage(env.thread(s"board/$key").addRes("n<><>d<> 本文 <>t", "1<>log", posting(create = true)).toFuture)
      _ = assertEquals(message, "lost response")
      _ = board.reserveThreadCreation = original
      allowed <- env.board("board").reserveThreadCreation("2000000002", creation).toFuture
      _ = assertEquals(allowed, "")
      meta <- env.boardInstance("board").state.storage.get[js.Any]("postRateMeta").toFuture
      _ = assert(meta.isEmpty)
    } yield ()
  }

  test("試行モードは板の予約が失敗しても書き込みを続ける") {
    val env = newEnv()
    val board = env.boardInstance("board").asInstanceOf[js.Dynamic]
    board.reservePost = ((_: String, _: js.Dynamic, _: js.Dynamic) => js.Promise.reject(new js.Error("unavailable"))): js.Function3[String, js.Dynamic, js.Dynamic, js.Promise[String]]
    for {
      result <- env.thread(s"board/$key").addRes("n<><>d<> 本文 <>t", "1<>log", posting(level = "log", create = true)).toFuture
      _ = assertEquals((result.count, result.swallowed, result.observed.toList), (1, false, Nil))
      dat <- env.thread(s"board/$key").getDat().toFuture
      _ = assertEquals(dat, "n<><>d<> 本文 <>t\n")
      history <- env.boardInstance("board").state.storage.get[js.Array[js.Dynamic]]("threadCreationHistory:0").toFuture
      _ = assertEquals(history.get(0).pending.asInstanceOf[Boolean], false)
    } yield ()
  }
}
