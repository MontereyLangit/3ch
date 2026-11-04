package io._3ch.bbs.durable

import munit.FunSuite
import scala.scalajs.js
import scala.scalajs.js.Thenable.Implicits.*
import scala.scalajs.js.JSConverters.*
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.concurrent.Future
import io._3ch.bbs.external.cloudflare.DurableObjectState
import io._3ch.bbs.durable.codec.ThreadCreationCodec

class ThreadCreationSuite extends FunSuite {
  private val ordinaryError = "thread-creation:narrow:ip"

  private def limit(identity: String, count: Int = 2): js.Dynamic = js.Dynamic.literal(
    ip = identity, userAgent = s"ua-$identity", networkGroup = s"network-$identity", title = s"title-$identity",
    tatesugi = count, tatesugiResetSeconds = 3600, junban = 0, junbanResetSeconds = 3600)

  private def posting(key: String, identity: String, create: Boolean = true): js.Dynamic = js.Dynamic.literal(
    bbs = "board", key = key, create = create, title = "title", sage = false, creationLimit = limit(identity))

  test("simultaneous reservations cannot both pass the same identity") {
    val board = DurableTestKit.newEnv().board("board")
    for {
      results <- Future.sequence(Seq(board.reserveThreadCreation("1", limit("a")).toFuture,
        board.reserveThreadCreation("2", limit("a")).toFuture))
      _ = assertEquals(results.count(_.isEmpty), 1)
      _ = assertEquals(results.count(_ == ordinaryError), 1)
    } yield ()
  }

  test("removing a thread does not erase its creation history") {
    val board = DurableTestKit.newEnv().board("board")
    for {
      _ <- board.reserveThreadCreation("1", limit("a")).toFuture
      _ <- board.completeThreadCreation("1").toFuture
      _ <- board.removeThread("1").toFuture
      blocked <- board.reserveThreadCreation("2", limit("a")).toFuture
      _ = assertEquals(blocked, ordinaryError)
    } yield ()
  }

  test("a reply bypasses creation regulation while a rejected creation writes no dat") {
    val env = DurableTestKit.newEnv()
    for {
      first <- env.thread("board/2000000001").addRes("name<><>date<> body <>title", "1<>log", posting("2000000001", "a")).toFuture
      _ = assertEquals(first, 1)
      reply <- env.thread("board/2000000001").addRes("name<><>date<> reply <>", "1<>log", posting("2000000001", "a", false)).toFuture
      _ = assertEquals(reply, 2)
      error <- DurableTestKit.rejectionMessage(env.thread("board/2000000002").addRes("name<><>date<> body <>title", "1<>log", posting("2000000002", "a")).toFuture)
      _ = assertEquals(error, ordinaryError)
      dat <- env.thread("board/2000000002").getDat().toFuture
      _ = assertEquals(dat, "")
    } yield ()
  }

  test("a dat write failure cancels the reservation") {
    val env = DurableTestKit.newEnv()
    val instance = env.threadInstance("board/2000000001")
    val storage = instance.state.storage.asInstanceOf[js.Dynamic]
    val originalPut = storage.put.asInstanceOf[js.Function2[js.Any, js.UndefOr[js.Any], js.Promise[js.Any]]]
    storage.updateDynamic("put")(((key: js.Any, value: js.UndefOr[js.Any]) => {
      if !js.isUndefined(key.asInstanceOf[js.Dynamic].dat) then js.Promise.reject(new js.Error("write failed"))
      else originalPut(key, value)
    }): js.Function2[js.Any, js.UndefOr[js.Any], js.Promise[js.Any]])
    for {
      error <- DurableTestKit.rejectionMessage(instance.addRes("name<><>date<> body <>title", "1<>log", posting("2000000001", "a")).toFuture)
      _ = assertEquals(error, "write failed")
      allowed <- env.board("board").reserveThreadCreation("2000000002", limit("a")).toFuture
      _ = assertEquals(allowed, "")
    } yield ()
  }

  test("a subject write failure after dat persistence does not cancel history") {
    val env = DurableTestKit.newEnv()
    val board = env.board("board")
    board.asInstanceOf[js.Dynamic].updateDynamic("updateThread")(
      ((_: js.Any, _: js.Any, _: js.Any) => js.Promise.reject(new js.Error("subject failed"))): js.Function3[js.Any, js.Any, js.Any, js.Any])
    for {
      error <- DurableTestKit.rejectionMessage(env.thread("board/2000000001").addRes("name<><>date<> body <>title", "1<>log", posting("2000000001", "a")).toFuture)
      _ = assertEquals(error, "subject failed")
      dat <- env.thread("board/2000000001").getDat().toFuture
      _ = assert(dat.nonEmpty)
      blocked <- board.reserveThreadCreation("2000000002", limit("a")).toFuture
      _ = assertEquals(blocked, ordinaryError)
    } yield ()
  }

  test("history crossing a storage page still enforces the count window") {
    val board = DurableTestKit.newEnv().board("board")
    for {
      _ <- (1 to 65).foldLeft(Future.unit) { (previous, number) =>
        previous.flatMap(_ => board.reserveThreadCreation(number.toString, limit(number.toString, 65)).toFuture)
          .flatMap { result =>
            assertEquals(result, "")
            board.completeThreadCreation(number.toString).toFuture
          }
      }
      blocked <- board.reserveThreadCreation("66", limit("1", 65)).toFuture
      _ = assertEquals(blocked, ordinaryError)
      allowed <- board.reserveThreadCreation("66", limit("66", 65)).toFuture
      _ = assertEquals(allowed, "")
      _ <- board.completeThreadCreation("66").toFuture
      outsideWindow <- board.reserveThreadCreation("67", limit("1", 65)).toFuture
      _ = assertEquals(outsideWindow, "")
    } yield ()
  }

  test("failed completion still publishes dat and subject while retaining the reservation") {
    val env = DurableTestKit.newEnv()
    val board = env.board("board")
    board.asInstanceOf[js.Dynamic].updateDynamic("completeThreadCreation")(
      ((_: String) => js.Promise.reject(new js.Error("completion failed"))): js.Function1[String, js.Promise[Unit]])
    for {
      count <- env.thread("board/2000000001").addRes("name<><>date<> body <>title", "1<>log", posting("2000000001", "a")).toFuture
      _ = assertEquals(count, 1)
      dat <- env.thread("board/2000000001").getDat().toFuture
      _ = assert(dat.nonEmpty)
      subject <- board.getSubject().toFuture
      _ = assertEquals(subject, "2000000001.dat<>title (1)\n")
      blocked <- board.reserveThreadCreation("2000000002", limit("a")).toFuture
      _ = assertEquals(blocked, ordinaryError)
    } yield ()
  }

  test("history keeps the existing storage format") {
    val instance = DurableTestKit.newEnv().boardInstance("board")
    val original = js.Dynamic.literal(key = "1", timestampSeconds = 2000000000.5, title = "title-a", ip = "a", userAgent = "ua-a",
      networkGroup = "network-a", pending = false, window = 3, expiresAt = 2000000120.0)
    assertEquals(js.JSON.stringify(ThreadCreationCodec.encodeRecord(ThreadCreationCodec.decodeRecord(original))), js.JSON.stringify(original))
    for {
      _ <- instance.state.storage.put(js.Dictionary[js.Any]("threadCreationPageCount" -> 1, "threadCreationHistory:0" -> js.Array(original))).toFuture
      blocked <- instance.reserveThreadCreation("2", limit("a")).toFuture
      _ = assertEquals(blocked, ordinaryError)
      page <- instance.state.storage.get[js.Array[js.Dynamic]]("threadCreationHistory:0").toFuture
      _ = assertEquals(js.JSON.stringify(page.get(0)), js.JSON.stringify(original))
    } yield ()
  }

  test("creation RPC failures reject outside the concurrency gate") {
    var gateRejections = 0
    val state = js.Dynamic.literal(
      storage = js.Dynamic.literal(get = ((_: String) => js.Promise.reject(new js.Error("storage failed"))): js.Function1[String, js.Promise[js.Any]]),
      blockConcurrencyWhile = ((callback: js.Function0[js.Promise[js.Any]]) => {
        callback().toFuture.recoverWith { case error =>
          gateRejections += 1
          Future.failed(error)
        }.toJSPromise
      }): js.Function1[js.Function0[js.Promise[js.Any]], js.Promise[js.Any]]
    ).asInstanceOf[DurableObjectState]
    val board = new Board(state, js.Dynamic.literal())
    for {
      reserve <- DurableTestKit.rejectionMessage(board.reserveThreadCreation("1", limit("a")).toFuture)
      complete <- DurableTestKit.rejectionMessage(board.completeThreadCreation("1").toFuture)
      cancel <- DurableTestKit.rejectionMessage(board.cancelThreadCreation("1").toFuture)
      _ = assertEquals(Seq(reserve, complete, cancel), Seq.fill(3)("storage failed"))
      _ = assertEquals(gateRejections, 0)
    } yield ()
  }
}
