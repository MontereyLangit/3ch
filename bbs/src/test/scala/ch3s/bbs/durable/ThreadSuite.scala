package ch3s.bbs.durable

import munit.FunSuite
import ch3s.bbs.external.cloudflare.*
import scala.scalajs.js
import scala.scalajs.js.Thenable.Implicits.*
import scala.scalajs.js.JSConverters.*
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.concurrent.Future

/** `Thread` DO を完全一致で固定し、replay ハーネスが踏まない境界を補う */
class ThreadSuite extends FunSuite {
  import DurableTestKit.{newEnv, seed, rejectionMessage}

  private val dat = "name<><>date<>message<>title\n"
  private def moveInput(bbs: String = "source", key: String = "1800000000", targetBoard: String = "target", targetKey: String = "1800000001"): js.Dynamic =
    js.Dynamic.literal(bbs = bbs, key = key, targetBoard = targetBoard, targetKey = targetKey)

  test("削除は本文・投稿者ログを保持し、旧R2データを復活させない（dat/legacyLog/settingsを完全一致で確認）") {
    val env = newEnv()
    val key = "1700000000"
    env.putR2(s"source/thread/1700/$key.cgi", "legacy title\nBBS_HEISA=checked\n1<>name<><>1700000000<>ID:one<>text<>IP:192.0.2.1\n")
    val source = env.thread(s"source/$key")
    // 1700000000 を Asia/Tokyo で Intl.DateTimeFormat 整形した固定値（タイムゾーン指定込みなのでシステムTZに依存しない）。
    val expectedDat = "name<><>2023/11/15(水) 07:13:20 ID:one<> text <>legacy title\n"
    for {
      before <- source.inspect("source", key).toFuture
      _ = assertEquals(before.legacyLog.toOption, Some("legacy title\nBBS_HEISA=checked\n1<>name<><>1700000000<>ID:one<>text<>IP:192.0.2.1\n"))
      _ = assertEquals(before.settings.toOption, Some("BBS_HEISA=checked"))
      _ = assertEquals(before.dat.toOption, Some(expectedDat))
      _ <- source.moderate("delete", js.Dynamic.literal(bbs = "source", key = key)).toFuture
      hiddenDat <- source.getDat("source", key).toFuture
      _ = assertEquals(hiddenDat, "", "hidden後のgetDatは空文字列")
      after <- source.inspect("source", key).toFuture
      _ = assertEquals(after.dat.toOption, Some(expectedDat), "delete後もstorage上のdatは保持される")
      _ = assertEquals(after.legacyLog.toOption, before.legacyLog.toOption, "旧R2ログはdelete後も保持される")
      removed <- env.board("source").getRemoved().toFuture
      _ = assertEquals(removed.toList, List(key))
    } yield ()
  }

  test("移動の途中失敗は同じ移動先への再実行で復旧し、ログ・停止状態を保持する（完全一致）") {
    val env = newEnv()
    val source = env.thread("source/1800000000")
    val destination = env.thread("target/1800000001")
    val sourceInstance = env.threadInstance("source/1800000000")
    val boardTargetOriginal = env.board("target").asInstanceOf[js.Dynamic]
    val originalUpdateThread = boardTargetOriginal.updateThread
    for {
      _ <- seed(sourceInstance, "dat" -> dat, "log" -> "1<>IP:192.0.2.1\n", "legacyLog" -> "old private log", "settings" -> "BBS_HEISA=checked", "stopped" -> true)
      // Board.updateThread を一時的に失敗させ、移動の後半だけが失敗するケースを再現する。
      _ = boardTargetOriginal.updateThread = ((_: js.Any, _: js.Any, _: js.Any) => js.Promise.reject(new js.Error("temporary failure"))): js.Function3[js.Any, js.Any, js.Any, js.Any]
      failMsg <- rejectionMessage(source.moderate("move", moveInput()).toFuture)
      _ = assert(failMsg.contains("temporary failure"), failMsg)
      datAfterFail <- source.getDat().toFuture
      _ = assertEquals(datAfterFail, "", "移動処理中(movingTo)はhidden相当でgetDatが空になる")
      destDatAfterFail <- destination.getDat().toFuture
      _ = assertEquals(destDatAfterFail, "")
      retryBlockedMsg <- rejectionMessage(source.addRes("new", "2<>log").toFuture)
      _ = assert(retryBlockedMsg.contains("移動"), retryBlockedMsg)
      // updateThreadを元の実装に戻すと、同じ移動先に対してだけ再開できる。
      _ = boardTargetOriginal.updateThread = originalUpdateThread
      restoredBoard = env.board("target")
      _ <- source.moderate("move", moveInput()).toFuture
      destDat <- destination.getDat().toFuture
      _ = assertEquals(destDat, dat)
      destLog <- destination.getLog().toFuture
      _ = assertEquals(destLog, "1<>IP:192.0.2.1\n")
      destInspect <- destination.inspect().toFuture
      _ = assertEquals(destInspect.legacyLog.toOption, Some("old private log"))
      destAddBlocked <- rejectionMessage(destination.addRes("new", "2<>log").toFuture)
      _ = assert(destAddBlocked.contains("停止"), destAddBlocked)
      subject <- restoredBoard.getSubject().toFuture
      _ = assertEquals(subject, "1800000001.dat<>title (1)\n")
      sourceInspect <- source.inspect().toFuture
      _ = assertEquals(sourceInspect.dat.toOption, Some(dat), "移動元のstorage上のdatはhidden化後も保持される")
    } yield ()
  }

  test("移動先保存後に応答を失っても二重コピーせず復旧できる") {
    val env = newEnv()
    val source = env.thread("source/1800000000")
    val destination = env.thread("target/1800000001")
    val destInstance = env.threadInstance("target/1800000001")
    for {
      _ <- source.setDat(dat).toFuture
      destDynamic = destInstance.asInstanceOf[js.Dynamic]
      originalReceiveMove = destDynamic.receiveMove
      // 素の関数参照を切り離して呼ぶと `this`(=destInstance)が失われクラスメソッド内の `this.state`
      // 参照が壊れるため、`Function.prototype.call` で明示的に `this` を渡して元の実装を呼ぶ。
      _ = destDynamic.receiveMove = ((data: js.Any, src: js.Any) =>
        originalReceiveMove.call(destDynamic, data, src).asInstanceOf[js.Promise[js.Any]].toFuture
          .flatMap(_ => Future.failed[js.Any](js.JavaScriptException(new js.Error("lost response")))).toJSPromise
      ): js.Function2[js.Any, js.Any, js.Promise[js.Any]]
      moveMsg <- rejectionMessage(source.moderate("move", moveInput()).toFuture)
      _ = assert(moveMsg.contains("lost response"), moveMsg)
      addBlockedMsg <- rejectionMessage(source.addRes("new", "2<>log").toFuture)
      _ = assert(addBlockedMsg.contains("移動"), addBlockedMsg)
      _ = destDynamic.receiveMove = originalReceiveMove
      _ <- source.moderate("move", moveInput()).toFuture
      destDat <- destination.getDat().toFuture
      _ = assertEquals(destDat, dat)
    } yield ()
  }

  test("既存スレ同士の相互移動はロック前に拒否し、元データを保持する") {
    val env = newEnv()
    val source = env.thread("source/1800000000")
    val destination = env.thread("target/1800000001")
    for {
      _ <- source.setDat(dat).toFuture
      _ <- destination.setDat(dat).toFuture
      failures <- Future.sequence(Seq(
        source.moderate("move", moveInput()).toFuture.failed,
        destination.moderate("move", js.Dynamic.literal(bbs = "target", key = "1800000001", targetBoard = "source", targetKey = "1800000000")).toFuture.failed
      ))
      _ = failures.foreach(e => assert(DurableTestKit.messageOf(e).contains("使用済み"), DurableTestKit.messageOf(e)))
      sourceDat <- source.getDat().toFuture
      _ = assertEquals(sourceDat, dat)
      destDat <- destination.getDat().toFuture
      _ = assertEquals(destDat, dat)
    } yield ()
  }

  test("同時投稿はレスとログの連番を保持し、停止後の投稿を拒否する（dat/logを完全一致で確認）") {
    val env = newEnv()
    val instance = env.threadInstance("source/1800000000")
    val thread = env.thread("source/1800000000")
    for {
      _ <- seed(instance, "dat" -> dat, "log" -> "1<>first\n")
      // JS版と同じ前提: 2つのaddRes呼び出しをawaitせず同時に発行すると、blockConcurrencyWhileの
      // キュー投入順（=Scalaが引数を評価する順）でロックの取得順が決まり、後続は前段の完了を待つ。
      counts <- Future.sequence(Seq(
        thread.addRes("second<><><><>", "2<>second").toFuture,
        thread.addRes("third<><><><>", "2<>third").toFuture
      ))
      _ = assertEquals(counts.toList, List(2, 3))
      finalDat <- thread.getDat().toFuture
      _ = assertEquals(finalDat, dat + "second<><><><>\nthird<><><><>\n")
      finalLog <- thread.getLog().toFuture
      _ = assertEquals(finalLog, "1<>first\n2<>second\n3<>third\n")
      _ <- thread.moderate("stop", moveInput(bbs = "source", key = "1800000000")).toFuture
      blocked <- rejectionMessage(thread.addRes("fourth", "4<>fourth").toFuture)
      _ = assert(blocked.contains("停止"), blocked)
    } yield ()
  }

  test("旧スレへの直接返信は移行し、旧設定の停止も強制する（addRes後のdat/logを完全一致で確認）") {
    val env = newEnv()
    env.putR2("source/thread/1700/1700000000.cgi", "legacy title\n\n1<>name<><>1700000000<>ID:one<>text<>IP:192.0.2.1\n")
    val source = env.thread("source/1700000000")
    for {
      count <- source.addRes("second<><><><>", "2<>log", js.Dynamic.literal(bbs = "source", key = "1700000000", title = "legacy title", create = false)).toFuture
      _ = assertEquals(count, 2)
      newDat <- source.getDat().toFuture
      _ = assertEquals(newDat, "name<><>2023/11/15(水) 07:13:20 ID:one<> text <>legacy title\nsecond<><><><>\n")
      newLog <- source.getLog().toFuture
      _ = assertEquals(newLog, "2<>log\n")
      _ = env.putR2("source/thread/1700/1700000001.cgi", "legacy title\nBBS_HEISA=checked\n1<>name<><>1700000000<>ID:one<>text<>IP:192.0.2.1\n")
      blockedMsg <- rejectionMessage(env.thread("source/1700000001").addRes("second<><><><>", "2<>log", js.Dynamic.literal(bbs = "source", key = "1700000001", create = false)).toFuture)
      _ = assert(blockedMsg.contains("停止"), blockedMsg)
    } yield ()
  }

  // ---------- ここから: replayハーネスが踏んでいない追加シナリオ ----------

  test("999件目は許可され、1000件目は「1000を超えた」で完全一致のメッセージと共に拒否される（KB-02）") {
    val env = newEnv()
    val instance998 = env.threadInstance("board1/1")
    val seeded998 = (1 to 998).map(i => s"$i<>x<><>msg<>\n").mkString
    for {
      _ <- seed(instance998, "dat" -> seeded998, "log" -> "")
      count <- env.thread("board1/1").addRes("999<>new<><>msg<>", "999<>log").toFuture
      _ = assertEquals(count, 999)
      dat999 <- env.thread("board1/1").getDat().toFuture
      _ = assertEquals(dat999, seeded998 + "999<>new<><>msg<>\n")

      instance999 = env.threadInstance("board1/2")
      seeded999 = (1 to 999).map(i => s"$i<>x<><>msg<>\n").mkString
      _ <- seed(instance999, "dat" -> seeded999, "log" -> "")
      msg <- rejectionMessage(env.thread("board1/2").addRes("1000<>new<><>msg<>", "1000<>log").toFuture)
      _ = assertEquals(msg, "このスレッドは1000を超えたのでもう書けません。")
      unchanged <- env.thread("board1/2").getDat().toFuture
      _ = assertEquals(unchanged, seeded999, "拒否された投稿はdatに反映されない")
    } yield ()
  }

  test("stop/resumeはBBS_HEISAだけを入れ替え、他の設定の順序・値を保ちながら文字列を完全一致で組み立てる") {
    val env = newEnv()
    val instance = env.threadInstance("board1/3")
    val thread = env.thread("board1/3")
    for {
      _ <- seed(instance, "dat" -> "1<>x<><>msg<>title\n", "settings" -> "FOO=bar<>BBS_HEISA=checked<>BAR=baz")
      _ <- thread.moderate("stop", moveInput(bbs = "board1", key = "3")).toFuture
      afterStop <- thread.getSettings().toFuture
      _ = assertEquals(afterStop, "FOO=bar<>BAR=baz<>BBS_HEISA=checked")
      stoppedInspect <- thread.inspect().toFuture
      _ = assertEquals(stoppedInspect.stopped.toOption, Some(true))
      blocked <- rejectionMessage(thread.addRes("x", "2<>log", js.Dynamic.literal(bbs = "board1", key = "3", create = false)).toFuture)
      _ = assert(blocked.contains("停止"), blocked)
      _ <- thread.moderate("resume", moveInput(bbs = "board1", key = "3")).toFuture
      afterResume <- thread.getSettings().toFuture
      _ = assertEquals(afterResume, "FOO=bar<>BAR=baz<>BBS_HEISA=")
      resumedInspect <- thread.inspect().toFuture
      _ = assertEquals(resumedInspect.stopped.toOption, Some(false))
    } yield ()
  }

  test("editRes: name/mail/messageだけをエスケープ・<br>変換し、識別ID欄とタイトル欄は素通りする") {
    val env = newEnv()
    val instance = env.threadInstance("board1/4")
    val thread = env.thread("board1/4")
    val row1 = "Name1<><>2023/01/01(日) 00:00:00 ID:xxx<> original message <>ThreadTitle"
    val row2 = "Name2<><>2023/01/01(日) 00:00:05 ID:yyy<> original message2 <>"
    for {
      _ <- seed(instance, "dat" -> (row1 + "\n" + row2 + "\n"))
      _ <- thread.moderate("editRes", js.Dynamic.literal(bbs = "board1", key = "4", number = "2", name = "New<Name>", mail = "test@example.com", message = "line1\nline2")).toFuture
      after <- thread.getDat().toFuture
      _ = assertEquals(after, row1 + "\n" + "New&lt;Name&gt;<>test@example.com<>2023/01/01(日) 00:00:05 ID:yyy<>line1 <br> line2<>" + "\n")
      // "edited" は inspect() が返すキー一覧（dat/log/legacyLog/settings/hidden/stopped/movedTo/
      // movingTo/importedFrom）に含まれない。moderate側で立てたフラグはstorageを直接読んで確認する。
      editedFlag <- instance.state.storage.get[Boolean]("edited").toFuture
      _ = assertEquals(editedFlag.getOrElse(false), true)
    } yield ()
  }

  test("moderate('deleteRes', ...): 先頭4フィールドだけを「あぼーん」に置き換え、5番目(タイトル)フィールドは残す") {
    val env = newEnv()
    val instance = env.threadInstance("board1/5")
    val thread = env.thread("board1/5")
    val row1 = "Name1<><>2023/01/01(日) 00:00:00 ID:xxx<> original message <>ThreadTitle"
    val row2 = "Name2<><>2023/01/01(日) 00:00:05 ID:yyy<> original message2 <>"
    for {
      _ <- seed(instance, "dat" -> (row1 + "\n" + row2 + "\n"))
      _ <- thread.moderate("deleteRes", js.Dynamic.literal(bbs = "board1", key = "5", number = "1")).toFuture
      after <- thread.getDat().toFuture
      _ = assertEquals(after, "あぼーん<>あぼーん<>あぼーん<>あぼーん<>ThreadTitle" + "\n" + row2 + "\n")
    } yield ()
  }

  test("moderate('editRes'/'deleteRes'): レス番号が範囲外なら完全一致のメッセージで拒否する") {
    val env = newEnv()
    val instance = env.threadInstance("board1/6")
    val thread = env.thread("board1/6")
    for {
      _ <- seed(instance, "dat" -> "1<>x<><>msg<>title\n")
      msg <- rejectionMessage(thread.moderate("deleteRes", js.Dynamic.literal(bbs = "board1", key = "6", number = "2")).toFuture)
      _ = assertEquals(msg, "レス番号が不正です。")
      msg0 <- rejectionMessage(thread.moderate("editRes", js.Dynamic.literal(bbs = "board1", key = "6", number = "0", name = "a", mail = "", message = "b")).toFuture)
      _ = assertEquals(msg0, "レス番号が不正です。")
    } yield ()
  }

  test("deleteRes(resNum): 全5フィールドを丸ごとあぼーん化し、resNum==lines.lengthの境界は無視する") {
    val env = newEnv()
    // deleteRes(Int) は管理用メソッド。storage を直接仕込むため生インスタンスで呼ぶ。
    val instance = env.threadInstance("board1/7")
    for {
      _ <- seed(instance, "dat" -> "1<>a<><>msg1<>title\n2<>b<><>msg2<>\n")
      _ <- instance.deleteRes(2).toFuture
      after <- instance.getDat().toFuture
      _ = assertEquals(after, "1<>a<><>msg1<>title\nあぼーん<>あぼーん<>あぼーん<>あぼーん<>あぼーん\n")
      // dat.split("\n") は末尾に空要素を含むため lines.length == 3。resNum(3) < 3 は偽になり、
      // 3番目(存在しない行)への書き込みは黙って無視される。
      _ <- instance.deleteRes(3).toFuture
      unchanged <- instance.getDat().toFuture
      _ = assertEquals(unchanged, after, "resNum(3) はlines.length(3)未満でないため無視される")
    } yield ()
  }

  test("null vs undefined: postingへ生のnullを渡してもundefined省略時と同じ挙動になる（旧: posting.isDefinedのnull/undefined不一致を修正）") {
    val env = newEnv()
    val instance = env.threadInstance("board1/8")
    val thread = env.thread("board1/8")
    // JSの `posting?.bbs` / `if (posting) ...` はnullに対してもundefinedと同じ「falsy」として振る舞う。
    // Scala側はDO越しに生のnullが来ても同じ結果になるよう、posting.isDefined(=「undefinedでない」)
    // ではなく「nullでもundefinedでもない」ことをチェックする。
    for {
      countWithNull <- thread.asInstanceOf[js.Dynamic].addRes("1<>a<><>msg<>title", "1<>log", null.asInstanceOf[js.Any]).asInstanceOf[js.Promise[Int]].toFuture
      _ = assertEquals(countWithNull, 1)
      datWithNull <- thread.getDat().toFuture
      _ = assertEquals(datWithNull, "1<>a<><>msg<>title\n")

      instanceOmitted = env.threadInstance("board1/9")
      threadOmitted = env.thread("board1/9")
      countOmitted <- threadOmitted.addRes("1<>a<><>msg<>title", "1<>log").toFuture
      _ = assertEquals(countOmitted, 1, "posting省略(undefined)時と同じ結果になる")
    } yield ()
  }
}
