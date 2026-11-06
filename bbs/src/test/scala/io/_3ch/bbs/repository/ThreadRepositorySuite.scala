package io._3ch.bbs.repository

import io._3ch.bbs.domain.posting.{ThreadPosting, ThreadWriteResult}
import io._3ch.bbs.domain.posting.moderation.{PostRateFinding, PostRateRejection, ThreadCreationRateLimit, ThreadCreationRejection}
import io._3ch.bbs.external.cloudflare.WorkerEnv
import io._3ch.common.domain.BoardId
import io._3ch.common.domain.posting.ThreadWriteRejection
import munit.FunSuite

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

class ThreadRepositorySuite extends FunSuite {
  private val board = BoardId.create("sample").toOption.get

  private def worker(thread: js.Dynamic): WorkerEnv = js.Dynamic.literal(
    THREAD_DO = js.Dynamic.literal(
      idFromName = ((name: String) => name): js.Function1[String, String],
      get = ((id: String) => thread): js.Function1[String, js.Dynamic]
    )
  ).asInstanceOf[WorkerEnv]

  private def append(result: => js.Promise[js.Dynamic]): Future[ThreadWriteResult] = {
    val thread = js.Dynamic.literal(addRes = (() => result): js.Function0[js.Promise[js.Dynamic]])
    ThreadRepository.addRes(board, "1700000000", "dat", "log", ThreadPosting(), worker(thread))
  }

  test("RPC の拒否コードをスレ状態・スレ立て・連投の型付き結果に戻す") {
    val creation = ThreadCreationRejection.Narrow(ThreadCreationRateLimit.Reason.Ip)
    val cases = ThreadWriteRejection.values.toSeq.map(r => r.code -> ThreadWriteResult.Refused(r)) ++ Seq(
      creation.code -> ThreadWriteResult.CreationLimited(creation),
      PostRateRejection.Samba24(7).code -> ThreadWriteResult.RateLimited(PostRateRejection.Samba24(7)),
      PostRateRejection.Fr(2).code -> ThreadWriteResult.RateLimited(PostRateRejection.Fr(2)),
      PostRateRejection.Gontakure.code -> ThreadWriteResult.RateLimited(PostRateRejection.Gontakure),
      PostRateRejection.ThreadRentou.code -> ThreadWriteResult.RateLimited(PostRateRejection.ThreadRentou)
    )
    Future.traverse(cases) { (code, expected) =>
      append(js.Promise.reject(new js.Error(code))).map(result => assertEquals(result, expected))
    }
  }

  test("未知の障害を拒否理由へ丸めず元の失敗を保つ") {
    val error = new IllegalStateException("storage unavailable")
    val thread = js.Dynamic.literal(addRes = (() => js.Promise.reject(error)): js.Function0[js.Promise[js.Dynamic]])
    ThreadRepository.addRes(board, "1700000000", "dat", "log", ThreadPosting(), worker(thread)).map(_ => fail("成功した")).recover {
      case actual => assertEquals(actual, error)
    }
  }

  test("確定番号と試行モードの判定を復号し、未知の記録コードを無視する") {
    for {
      written <- append(js.Promise.resolve(js.Dynamic.literal(count = 3, swallowed = false, observed = js.Array("post-rate:board:fr:2", "unknown", "post-rate:thread:copipe"))))
      swallowed <- append(js.Promise.resolve(js.Dynamic.literal(count = 4, swallowed = true, observed = js.Array[String]())))
    } yield {
      assertEquals(written, ThreadWriteResult.Written(3, Seq(PostRateFinding.Rejected(PostRateRejection.Fr(2)), PostRateFinding.Copipe)))
      assertEquals(swallowed, ThreadWriteResult.Swallowed(4))
    }
  }

  test("スレ主の壊れた値はスナップショットを読む段では復号しない") {
    val brokenOwner = js.Dynamic.literal()
    js.Object.defineProperty(brokenOwner, "ip", js.Dynamic.literal(
      get = (() => throw js.JavaScriptException(new js.Error("broken owner"))): js.Function0[String]
    ).asInstanceOf[js.PropertyDescriptor])
    val thread = js.Dynamic.literal(
      getDat = (() => js.Promise.resolve("dat")): js.Function0[js.Promise[String]],
      getSettings = (() => js.Promise.resolve("BBS_TITLE=スレ<>")): js.Function0[js.Promise[String]],
      getOwner = (() => js.Promise.resolve[js.Any](brokenOwner)): js.Function0[js.Promise[js.Any]]
    )
    ThreadRepository.store(worker(thread)).readForReply(board, "1700000000").map { snapshot =>
      assertEquals(snapshot.dat, "dat")
      assertEquals(snapshot.settings.get("BBS_TITLE"), Some("スレ"))
      intercept[js.JavaScriptException](snapshot.owner())
    }
  }
}
