package io._3ch.bbs.durable

import munit.FunSuite
import io._3ch.bbs.external.cloudflare.*
import scala.collection.mutable
import scala.concurrent.Future
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.scalajs.js.Thenable.Implicits.*
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

/** `Board.updateThread` の並び順と打ち切りを subjectList の完全一致で固定し、連投履歴の RPC の直列化と保存を確かめる */
class BoardSuite extends FunSuite {

  private def data(key: String, title: js.UndefOr[String], resNum: Int): js.Dynamic =
    js.Dynamic.literal(key = key, title = title, resNum = resNum)

  test("sageは既存行をその場で更新し、ageは先頭へ移動する。タイトル省略時は既存行から抽出する") {
    val env = DurableTestKit.newEnv()
    val board = env.board("board1")
    for {
      _ <- board.updateThread(data("A", "TitleA", 1), false).toFuture
      s1 <- board.getSubject().toFuture
      _ = assertEquals(s1, "A.dat<>TitleA (1)\n")
      _ <- board.updateThread(data("B", "TitleB", 1), false).toFuture
      s2 <- board.getSubject().toFuture
      _ = assertEquals(s2, "B.dat<>TitleB (1)\nA.dat<>TitleA (1)\n", "新規スレは常に先頭に追加される")
      // sage: 既存(A, index=1)を更新しても位置は変わらない。titleを省略すると既存行から抽出される。
      _ <- board.updateThread(data("A", js.undefined, 2), true).toFuture
      s3 <- board.getSubject().toFuture
      _ = assertEquals(s3, "B.dat<>TitleB (1)\nA.dat<>TitleA (2)\n", "sageは位置を維持し、抽出したタイトルで更新する")
      // age: 既存(A, index=1)への更新は先頭へ移動する。
      _ <- board.updateThread(data("A", "TitleA", 3), false).toFuture
      s4 <- board.getSubject().toFuture
      _ = assertEquals(s4, "A.dat<>TitleA (3)\nB.dat<>TitleB (1)\n", "ageは先頭に移動する")
    } yield ()
  }

  test("maxThreadsを超えた分は末尾（＝古い方）から切り捨てられる") {
    val env = DurableTestKit.newEnv()
    val board = env.board("board1")
    for {
      _ <- board.updateThread(data("T1", "A", 1), false, 2).toFuture
      _ <- board.updateThread(data("T2", "B", 1), false, 2).toFuture
      _ <- board.updateThread(data("T3", "C", 1), false, 2).toFuture
      subject <- board.getSubject().toFuture
      _ = assertEquals(subject, "T3.dat<>C (1)\nT2.dat<>B (1)\n", "T1(最古)がmaxThreads=2により切り捨てられる")
    } yield ()
  }

  test("KB相当の非自明な分岐: maxThreadsが負の場合はArray.slice(0,負)と同じ「末尾からその分だけ切る」挙動になる") {
    val env = DurableTestKit.newEnv()
    val board = env.board("board1")
    for {
      _ <- board.updateThread(data("T1", "A", 1), false, 700).toFuture
      _ <- board.updateThread(data("T2", "B", 1), false, 700).toFuture
      _ <- board.updateThread(data("T3", "C", 1), false, 700).toFuture
      before <- board.getSubject().toFuture
      _ = assertEquals(before, "T3.dat<>C (1)\nT2.dat<>B (1)\nT1.dat<>A (1)\n")
      // maxThreads=-1 は `lines.slice(0, -1)` 相当 = 末尾から1件だけ切り捨てる（`take(-1)`にしてはいけない、
      // というBoard.scalaのコメント通りの挙動をピン留めする）。sage指定でこの1回の呼び出し自体は
      // 並び順を変えない。
      _ <- board.updateThread(data("T3", js.undefined, 2), true, -1).toFuture
      after <- board.getSubject().toFuture
      _ = assertEquals(after, "T3.dat<>C (2)\nT2.dat<>B (1)\n", "T1(末尾)だけが切り捨てられ、T3は位置と件数のみ更新される")
    } yield ()
  }

  test("subjectListが512KBを超えたら、512KB以下になるまで末尾の行から削除する") {
    val env = DurableTestKit.newEnv()
    val board = env.board("board1")
    val capBytes = 512 * 1024
    // "huge.dat<>" + title + " (1)" + 末尾の"\n"(renderが1行の時に付与する分) の可変部分(title)以外の
    // 固定長。ASCIIのみなのでUTF-8バイト長==文字数。
    val fixedOverhead = "huge.dat<>".length + " (1)".length + "\n".length
    // 行1本だけでちょうどcapBytesになるよう調整する。閾値判定は`> capBytes`(厳密に超えた場合のみ)
    // なので、これ単体ではまだ削除されないことを先に確認してから、2件目を足して押し出す。
    val hugeTitle = "a".repeat(capBytes - fixedOverhead)
    for {
      _ <- board.updateThread(data("huge", hugeTitle, 1), false, 700).toFuture
      beforeAdd <- board.getSubject().toFuture
      _ = assertEquals(beforeAdd.length, capBytes, "前提: 巨大行1本だけでちょうど512KB（`>`条件なので単体では削除されない）")
      // 新しい小さいスレを先頭に追加すると合計サイズが512KBを超えるため、あふれた分（末尾＝最古の
      // huge行）が丸ごと削除される。
      _ <- board.updateThread(data("new", "New", 1), false, 700).toFuture
      after <- board.getSubject().toFuture
      _ = assertEquals(after, "new.dat<>New (1)\n", "512KBの壁を超えた巨大な既存行(末尾)が丸ごと切り捨てられる")
    } yield ()
  }

  test("removeThread: 対象keyの行だけを削除し、removedリストは重複を追加しない") {
    val env = DurableTestKit.newEnv()
    val board = env.board("board1")
    for {
      _ <- board.updateThread(data("A", "TitleA", 1), false).toFuture
      _ <- board.updateThread(data("B", "TitleB", 1), false).toFuture
      _ <- board.removeThread("A").toFuture
      subject <- board.getSubject().toFuture
      _ = assertEquals(subject, "B.dat<>TitleB (1)\n")
      removed1 <- board.getRemoved().toFuture
      _ = assertEquals(removed1.toList, List("A"))
      _ <- board.removeThread("A").toFuture // 同じkeyをもう一度削除しても重複しない
      removed2 <- board.getRemoved().toFuture
      _ = assertEquals(removed2.toList, List("A"))
      _ <- board.removeThread("B").toFuture
      subjectAfterAll <- board.getSubject().toFuture
      _ = assertEquals(subjectAfterAll, "", "全スレ削除後のsubjectListは空文字列")
      removed3 <- board.getRemoved().toFuture
      _ = assertEquals(removed3.toList, List("A", "B"))
    } yield ()
  }

  test("板にスレが無い場合、getSubjectは空文字列を返す") {
    val env = DurableTestKit.newEnv()
    val board = env.board("emptyboard")
    for {
      subject <- board.getSubject().toFuture
      _ = assertEquals(subject, "")
      removed <- board.getRemoved().toFuture
      _ = assertEquals(removed.toList, Nil)
    } yield ()
  }

  private def entry(ip: String): js.Dynamic = js.Dynamic.literal(ip = ip, shiki = s"shiki-$ip", aid = "")
  private def rate(samba24: Int = 10, timecount: Int = 18, timeclose: Int = 10, enforce: Boolean = true): js.Dynamic =
    js.Dynamic.literal(timecount = timecount, timeclose = timeclose, samba24 = samba24, keys = "ip,shiki,aid", enforce = enforce)

  /** storage.put に渡されたキーを記録する */
  private def recordPuts(instance: Board): mutable.Buffer[Set[String]] = {
    val puts = mutable.Buffer.empty[Set[String]]
    val storage = instance.state.storage.asInstanceOf[js.Dynamic]
    val original = storage.put.asInstanceOf[js.Function2[js.Any, js.UndefOr[js.Any], js.Promise[js.Any]]]
    storage.updateDynamic("put")(((key: js.Any, value: js.UndefOr[js.Any]) => {
      puts += (if value.isDefined then Set(key.asInstanceOf[String]) else key.asInstanceOf[js.Dictionary[js.Any]].keys.toSet)
      original(key, value)
    }): js.Function2[js.Any, js.UndefOr[js.Any], js.Promise[js.Any]])
    puts
  }

  test("連投の予約は直列化され、同じ投稿者の同時の予約は片方だけ通る") {
    val board = DurableTestKit.newEnv().board("board1")
    for {
      results <- Future.sequence(Seq(board.reservePost("1:a", entry("a"), rate()).toFuture, board.reservePost("2:b", entry("a"), rate()).toFuture))
      _ = assertEquals(results.count(_.isEmpty), 1)
      _ = assert(results.exists(_.startsWith("post-rate:board:samba24:")), results.toString)
      other <- board.reservePost("3:c", entry("b"), rate()).toFuture
      _ = assertEquals(other, "")
    } yield ()
  }

  test("試行モードは拒否コードを返しつつ予約し、拒否する設定では記録しない") {
    val board = DurableTestKit.newEnv().board("board1")
    for {
      _ <- board.reservePost("1:a", entry("a"), rate(samba24 = 0, timeclose = 2)).toFuture
      logged <- board.reservePost("2:a", entry("a"), rate(samba24 = 0, timeclose = 2, enforce = false)).toFuture
      _ = assertEquals(logged, "")
      rejected <- board.reservePost("3:a", entry("a"), rate(samba24 = 0, timeclose = 2)).toFuture
      _ = assertEquals(rejected, "post-rate:board:gontakure")
      loggedAgain <- board.reservePost("4:a", entry("a"), rate(samba24 = 0, timeclose = 2, enforce = false)).toFuture
      _ = assertEquals(loggedAgain, "post-rate:board:gontakure")
      _ <- board.cancelPost("4:a").toFuture
      _ <- board.cancelPost("2:a").toFuture
      allowed <- board.reservePost("5:a", entry("a"), rate(samba24 = 0, timeclose = 2)).toFuture
      _ = assertEquals(allowed, "", "取り消した予約は数えない")
    } yield ()
  }

  test("予約は最新ページとメタだけを書き、確定と取消はその記録を含むページだけを書く") {
    val instance = DurableTestKit.newEnv().boardInstance("board1")
    val settings = rate(samba24 = 0, timecount = 100)
    for {
      _ <- (1 to 65).foldLeft(Future.unit)((acc, n) => acc.flatMap(_ => instance.reservePost(s"k$n", entry(s"ip$n"), settings).toFuture.map(_ => ())))
      puts = recordPuts(instance)
      _ <- instance.reservePost("k66", entry("ip66"), settings).toFuture
      _ = assertEquals(puts.toList, List(Set("postRateMeta", "postRateHistory:1")))
      _ <- instance.completePost("k64").toFuture
      _ <- instance.cancelPost("k66").toFuture
      _ <- instance.completePost("missing").toFuture
      _ = assertEquals(puts.toList.drop(1), List(Set("postRateHistory:0"), Set("postRateHistory:1")))
      meta <- instance.state.storage.get[js.Dynamic]("postRateMeta").toFuture
      _ = assertEquals(js.JSON.stringify(meta.get), """{"newest":1,"oldest":0}""")
      page <- instance.state.storage.get[js.Array[js.Dynamic]]("postRateHistory:1").toFuture
      _ = assertEquals(page.get.toList.map(_.key.asInstanceOf[String]), List("k65"))
      first <- instance.state.storage.get[js.Array[js.Dynamic]]("postRateHistory:0").toFuture
      _ = assertEquals(first.get(63).status.asInstanceOf[String], "completed")
      _ = assertEquals(js.Object.keys(first.get(0).asInstanceOf[js.Object]).toList, List("key", "t", "ip", "shiki", "aid", "status"))
    } yield ()
  }

  test("連投履歴の RPC の失敗はロックの外で投げ直す") {
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
      reserve <- DurableTestKit.rejectionMessage(board.reservePost("1", entry("a"), rate()).toFuture)
      complete <- DurableTestKit.rejectionMessage(board.completePost("1").toFuture)
      cancel <- DurableTestKit.rejectionMessage(board.cancelPost("1").toFuture)
      _ = assertEquals(Seq(reserve, complete, cancel), Seq.fill(3)("storage failed"))
      _ = assertEquals(gateRejections, 0)
    } yield ()
  }
}
