package io._3ch.bbs.durable

import munit.FunSuite
import io._3ch.bbs.external.cloudflare.*
import scala.scalajs.js
import scala.scalajs.js.Thenable.Implicits.*
import scala.scalajs.js.JSConverters.*
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.concurrent.Future

/** Thread DO の直列化・一括保存・拒否のコードの返し方。追記できるかの規則は ThreadWriteGuardSuite、連投規制の判定は PostRateLimitSuite で確かめる */
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
      _ = assertEquals(retryBlockedMsg, "thread-write:unavailable")
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
      _ = assertEquals(destAddBlocked, "thread-write:unavailable")
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
      _ = assertEquals(addBlockedMsg, "thread-write:unavailable")
      _ = destDynamic.receiveMove = originalReceiveMove
      _ <- source.moderate("move", moveInput()).toFuture
      destDat <- destination.getDat().toFuture
      _ = assertEquals(destDat, dat)
    } yield ()
  }

  private val owner = js.Dynamic.literal(ip = "iphash", shikibetsu = "JP.xx", account = "")
  private def createWith(env: DurableTestKit.Env, name: String, key: String, extra: js.Dynamic): js.Promise[ThreadAddResult] = {
    val posting = js.Dynamic.literal(bbs = name.takeWhile(_ != '/'), key = key, title = "title", create = true, sage = false, maxThreads = 700d)
    js.Object.assign(posting.asInstanceOf[js.Object], extra.asInstanceOf[js.Object])
    env.thread(name).addRes("name<><>date<> body <>title", "1<>log", posting)
  }

  test("スレ立てのaddResはownerを同じバッチで保存し、getOwnerで読める") {
    val env = newEnv()
    for {
      _ <- createWith(env, "source/1800000000", "1800000000", js.Dynamic.literal(owner = owner)).toFuture
      stored <- env.thread("source/1800000000").getOwner().toFuture
      _ = assertEquals(js.JSON.stringify(stored), """{"ip":"iphash","shikibetsu":"JP.xx","account":""}""")
    } yield ()
  }

  test("ownerを渡さないスレ立てと返信はownerキーを作らない") {
    val env = newEnv()
    for {
      _ <- createWith(env, "source/1800000000", "1800000000", js.Dynamic.literal()).toFuture
      _ <- env.thread("source/1800000000").addRes("r<><><><>", "2<>log", js.Dynamic.literal(bbs = "source", key = "1800000000", title = "title", create = false, owner = owner)).toFuture
      stored <- env.thread("source/1800000000").getOwner().toFuture
      _ = assert(stored == null || js.isUndefined(stored), "ownerなしで作成したスレに後からownerは付かない")
    } yield ()
  }

  test("移動はownerを移動先へ引き継ぎ、ownerが無ければキーを作らない") {
    val env = newEnv()
    for {
      _ <- createWith(env, "source/1800000000", "1800000000", js.Dynamic.literal(owner = owner)).toFuture
      _ <- createWith(env, "source/1800000002", "1800000002", js.Dynamic.literal()).toFuture
      _ <- env.thread("source/1800000000").moderate("move", moveInput()).toFuture
      _ <- env.thread("source/1800000002").moderate("move", moveInput(key = "1800000002", targetKey = "1800000003")).toFuture
      moved <- env.thread("target/1800000001").getOwner().toFuture
      none <- env.thread("target/1800000003").getOwner().toFuture
      _ = assertEquals(js.JSON.stringify(moved), """{"ip":"iphash","shikibetsu":"JP.xx","account":""}""")
      _ = assert(none == null || js.isUndefined(none))
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
        thread.addRes("second<><><><>", "2<>second").toFuture.map(_.count),
        thread.addRes("third<><><><>", "2<>third").toFuture.map(_.count)
      ))
      _ = assertEquals(counts.toList, List(2, 3))
      finalDat <- thread.getDat().toFuture
      _ = assertEquals(finalDat, dat + "second<><><><>\nthird<><><><>\n")
      finalLog <- thread.getLog().toFuture
      _ = assertEquals(finalLog, "1<>first\n2<>second\n3<>third\n")
      _ <- thread.moderate("stop", moveInput(bbs = "source", key = "1800000000")).toFuture
      blocked <- rejectionMessage(thread.addRes("fourth", "4<>fourth").toFuture)
      _ = assertEquals(blocked, "thread-write:unavailable")
    } yield ()
  }

  test("旧スレへの直接返信は移行し、旧設定の停止も強制する（addRes後のdat/logを完全一致で確認）") {
    val env = newEnv()
    env.putR2("source/thread/1700/1700000000.cgi", "legacy title\n\n1<>name<><>1700000000<>ID:one<>text<>IP:192.0.2.1\n")
    val source = env.thread("source/1700000000")
    for {
      count <- source.addRes("second<><><><>", "2<>log", js.Dynamic.literal(bbs = "source", key = "1700000000", title = "legacy title", create = false)).toFuture.map(_.count)
      _ = assertEquals(count, 2)
      newDat <- source.getDat().toFuture
      _ = assertEquals(newDat, "name<><>2023/11/15(水) 07:13:20 ID:one<> text <>legacy title\nsecond<><><><>\n")
      newLog <- source.getLog().toFuture
      _ = assertEquals(newLog, "2<>log\n")
      _ = env.putR2("source/thread/1700/1700000001.cgi", "legacy title\nBBS_HEISA=checked\n1<>name<><>1700000000<>ID:one<>text<>IP:192.0.2.1\n")
      blockedMsg <- rejectionMessage(env.thread("source/1700000001").addRes("second<><><><>", "2<>log", js.Dynamic.literal(bbs = "source", key = "1700000001", create = false)).toFuture)
      _ = assertEquals(blockedMsg, "thread-write:closed")
    } yield ()
  }

  // ---------- ここから: replayハーネスが踏んでいない追加シナリオ ----------

  test("999件目は保存し、1000件目は拒否のコードで失敗して dat を変えない（KB-02）") {
    val env = newEnv()
    val instance998 = env.threadInstance("board1/1")
    val seeded998 = (1 to 998).map(i => s"$i<>x<><>msg<>\n").mkString
    for {
      _ <- seed(instance998, "dat" -> seeded998, "log" -> "")
      count <- env.thread("board1/1").addRes("999<>new<><>msg<>", "999<>log").toFuture.map(_.count)
      _ = assertEquals(count, 999)
      dat999 <- env.thread("board1/1").getDat().toFuture
      _ = assertEquals(dat999, seeded998 + "999<>new<><>msg<>\n")

      instance999 = env.threadInstance("board1/2")
      seeded999 = (1 to 999).map(i => s"$i<>x<><>msg<>\n").mkString
      _ <- seed(instance999, "dat" -> seeded999, "log" -> "")
      msg <- rejectionMessage(env.thread("board1/2").addRes("1000<>new<><>msg<>", "1000<>log").toFuture)
      _ = assertEquals(msg, "thread-write:full")
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
      _ = assertEquals(blocked, "thread-write:unavailable")
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
      countWithNull <- thread.asInstanceOf[js.Dynamic].addRes("1<>a<><>msg<>title", "1<>log", null.asInstanceOf[js.Any]).asInstanceOf[js.Promise[ThreadAddResult]].toFuture.map(_.count)
      _ = assertEquals(countWithNull, 1)
      datWithNull <- thread.getDat().toFuture
      _ = assertEquals(datWithNull, "1<>a<><>msg<>title\n")

      instanceOmitted = env.threadInstance("board1/9")
      threadOmitted = env.thread("board1/9")
      countOmitted <- threadOmitted.addRes("1<>a<><>msg<>title", "1<>log").toFuture.map(_.count)
      _ = assertEquals(countOmitted, 1, "posting省略(undefined)時と同じ結果になる")
    } yield ()
  }

  test("拒否した追記はスレ立て規制の予約も保存もせず、log の番号は確定した番号に置き換える") {
    val env = newEnv()
    val instance = env.threadInstance("board1/10")
    val thread = env.thread("board1/10")
    val creation = js.Dynamic.literal(ip = "ip", userAgent = "ua", networkGroup = "net", title = "t", tatesugi = 1, tatesugiResetSeconds = 3600, junban = 0, junbanResetSeconds = 3600)
    def create(key: String) = js.Dynamic.literal(bbs = "board1", key = key, title = "t", create = true, creationLimit = creation)
    for {
      _ <- seed(instance, "settings" -> "BBS_HEISA=checked<>")
      closed <- rejectionMessage(thread.addRes("d<><><> m <>t", "1<>log", create("10")).toFuture)
      _ = assertEquals(closed, "thread-write:closed")
      stored <- instance.state.storage.get[String]("dat").toFuture
      _ = assertEquals(stored.toOption.getOrElse(""), "")
      // 予約が残っていれば同じ識別情報の次のスレ立ては狭域規制で拒否される
      count <- env.thread("board1/11").addRes("d<><><> m <>t", "7<>log", create("11")).toFuture.map(_.count)
      _ = assertEquals(count, 1)
      log <- env.thread("board1/11").getLog().toFuture
      _ = assertEquals(log, "1<>log\n")
    } yield ()
  }

  private def rateLimit(level: String = "", ip: String = "ip-a", samba24: Int = 0, threadRentou: Int = 40): js.Dynamic = js.Dynamic.literal(
    level = level, ip = ip, shiki = s"shiki-$ip", aid = "", timecount = 18, timeclose = 10, samba24 = samba24, fr = 0,
    threadRentou = threadRentou, threadRentouReset = 3600, keys = "ip,shiki,aid")
  private def rated(key: String, limit: js.Dynamic, create: Boolean = false): js.Dynamic =
    js.Dynamic.literal(bbs = "board1", key = key, title = "t", create = create, rateLimit = limit)

  test("rateLimit が無ければ連投規制を判定せず、recentPosts も板の連投履歴も書かない") {
    val env = newEnv()
    val instance = env.threadInstance("board1/1800000020")
    for {
      _ <- seed(instance, "dat" -> "n<><>d<> 同じ <>t\n", "log" -> "1<>log\n")
      result <- env.thread("board1/1800000020").addRes("n<><>d<> 同じ <>", "2<>log", js.Dynamic.literal(bbs = "board1", key = "1800000020", create = false)).toFuture
      _ = assertEquals((result.count, result.swallowed, result.observed.toList), (2, false, Nil))
      recent <- instance.state.storage.get[js.Any]("recentPosts").toFuture
      _ = assert(recent.isEmpty)
      meta <- env.boardInstance("board1").state.storage.get[js.Any]("postRateMeta").toFuture
      _ = assert(meta.isEmpty)
    } yield ()
  }

  test("recentPosts は dat・log と同じ put で保存し、板の連投予約を確定する") {
    val env = newEnv()
    val instance = env.threadInstance("board1/1800000021")
    val storage = instance.state.storage.asInstanceOf[js.Dynamic]
    val original = storage.put.asInstanceOf[js.Function2[js.Any, js.UndefOr[js.Any], js.Promise[js.Any]]]
    var keys = List.empty[List[String]]
    storage.updateDynamic("put")(((key: js.Any, value: js.UndefOr[js.Any]) => {
      if !value.isDefined then keys = keys :+ key.asInstanceOf[js.Dictionary[js.Any]].keys.toList.sorted
      original(key, value)
    }): js.Function2[js.Any, js.UndefOr[js.Any], js.Promise[js.Any]])
    for {
      result <- env.thread("board1/1800000021").addRes("n<><>d<> 本文 <>t", "1<>log", rated("1800000021", rateLimit(), create = true)).toFuture
      _ = assertEquals(result.count, 1)
      _ = assertEquals(keys, List(List("dat", "log", "modifiedAt", "recentPosts", "settings")))
      recent <- instance.state.storage.get[js.Array[js.Dynamic]]("recentPosts").toFuture
      _ = assertEquals(recent.get.toList.map(_.ip.asInstanceOf[String]), List("ip-a"))
      page <- env.boardInstance("board1").state.storage.get[js.Array[js.Dynamic]]("postRateHistory:0").toFuture
      _ = assertEquals(page.get.toList.map(p => (p.key.asInstanceOf[String].startsWith("1800000021:"), p.status.asInstanceOf[String])), List((true, "completed")))
    } yield ()
  }

  test("コピペは on なら dat・log を変えずに吸い込み、log では書き込んで記録だけする") {
    val env = newEnv()
    val first = "n<><>d<> 同じ本文 <>t\n"
    for {
      _ <- seed(env.threadInstance("board1/1800000022"), "dat" -> first, "log" -> "1<>log\n")
      swallowed <- env.thread("board1/1800000022").addRes("m<><>e<> 同じ本文 <>", "2<>log", rated("1800000022", rateLimit(ip = "ip-b"))).toFuture
      _ = assertEquals((swallowed.count, swallowed.swallowed, swallowed.observed.toList), (2, true, Nil))
      dat <- env.thread("board1/1800000022").getDat().toFuture
      log <- env.thread("board1/1800000022").getLog().toFuture
      _ = assertEquals((dat, log), (first, "1<>log\n"))
      page <- env.boardInstance("board1").state.storage.get[js.Array[js.Dynamic]]("postRateHistory:0").toFuture
      _ = assertEquals(page.get.toList.map(_.status.asInstanceOf[String]), List("completed"), "吸い込んだ投稿も連投履歴に数える")
      logged <- env.thread("board1/1800000022").addRes("m<><>e<> 同じ本文 <>", "2<>log", rated("1800000022", rateLimit(level = "log", ip = "ip-c"))).toFuture
      _ = assertEquals((logged.count, logged.swallowed, logged.observed.toList), (2, false, List("post-rate:thread:copipe")))
      after <- env.thread("board1/1800000022").getDat().toFuture
      _ = assertEquals(after, first + "m<><>e<> 同じ本文 <>\n")
    } yield ()
  }

  test("スレ単位の連投は on なら板に予約せず拒否し、>>1 には適用しない") {
    val env = newEnv()
    for {
      _ <- env.thread("board1/1800000023").addRes("n<><>d<> 1 <>t", "1<>log", rated("1800000023", rateLimit(threadRentou = 1), create = true)).toFuture
      rejected <- rejectionMessage(env.thread("board1/1800000023").addRes("n<><>d<> 2 <>", "2<>log", rated("1800000023", rateLimit(threadRentou = 1))).toFuture)
      _ = assertEquals(rejected, "post-rate:thread:rentou")
      page <- env.boardInstance("board1").state.storage.get[js.Array[js.Dynamic]]("postRateHistory:0").toFuture
      _ = assertEquals(page.get.length, 1)
      logged <- env.thread("board1/1800000023").addRes("n<><>d<> 2 <>", "2<>log", rated("1800000023", rateLimit(level = "log", threadRentou = 1))).toFuture
      _ = assertEquals(logged.observed.toList, List("post-rate:thread:rentou"))
      other <- env.thread("board1/1800000023").addRes("n<><>d<> 3 <>", "3<>log", rated("1800000023", rateLimit(ip = "ip-z", threadRentou = 1))).toFuture
      _ = assertEquals(other.count, 3)
    } yield ()
  }

  test("level が 0 なら板に広域連投規制を外して予約し、空の旧形式は既定どおり判定する") {
    val env = newEnv()
    def sameShiki(level: String, ip: String): js.Dynamic = {
      val limit = rateLimit(level = level, ip = ip)
      limit.shiki = "shiki-ip-a"
      limit.fr = 10
      rated("1800000026", limit)
    }
    for {
      _ <- env.thread("board1/1800000026").addRes("n<><>d<> 1 <>t", "1<>log", rated("1800000026", rateLimit(level = "1"), create = true)).toFuture
      allowed <- env.thread("board1/1800000026").addRes("n<><>d<> 2 <>", "2<>log", sameShiki("0", "ip-b")).toFuture
      _ = assertEquals(allowed.count, 2)
      rejected <- rejectionMessage(env.thread("board1/1800000026").addRes("n<><>d<> 3 <>", "3<>log", sameShiki("", "ip-c")).toFuture)
      _ = assertEquals(rejected, "post-rate:board:fr:10")
    } yield ()
  }

  test("板単位で拒否したスレ立てはスレ立て規制の予約を取り消し、dat を書かない") {
    val env = newEnv()
    val creation = js.Dynamic.literal(ip = "ip", userAgent = "ua", networkGroup = "net", title = "t", tatesugi = 1, tatesugiResetSeconds = 3600, junban = 0, junbanResetSeconds = 3600)
    def create(key: String, other: String = "ip-b") = {
      val posting = rated(key, rateLimit(ip = other, samba24 = 10), create = true)
      posting.creationLimit = creation
      posting
    }
    for {
      _ <- env.board("board1").reservePost("x:1", js.Dynamic.literal(ip = "ip-a", shiki = "", aid = ""), js.Dynamic.literal(timecount = 18, timeclose = 10, samba24 = 10, keys = "ip", enforce = true)).toFuture
      rejected <- rejectionMessage(env.thread("board1/1800000024").addRes("n<><>d<> 1 <>t", "1<>log", create("1800000024", "ip-a")).toFuture)
      _ = assert(rejected.startsWith("post-rate:board:samba24:"), rejected)
      dat <- env.thread("board1/1800000024").getDat().toFuture
      _ = assertEquals(dat, "")
      created <- env.thread("board1/1800000025").addRes("n<><>d<> 1 <>t", "1<>log", create("1800000025")).toFuture
      _ = assertEquals(created.count, 1, "取り消したスレ立て予約は残らない")
    } yield ()
  }
}
