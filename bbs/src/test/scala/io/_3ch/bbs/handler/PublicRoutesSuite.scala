package io._3ch.bbs.handler

import munit.FunSuite
import io._3ch.bbs.durable.DurableTestKit
import io._3ch.bbs.durable.DurableTestKit.Env
import io._3ch.bbs.handler.HandlerTestKit.*
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

/** 公開ルート（`PublicRoutes.fetch`）の現仕様固定。KB-nn は docs/既知不具合台帳.md を参照。 */
class PublicRoutesSuite extends FunSuite {

  override def beforeEach(context: BeforeEach): Unit = installCaches()
  override def afterEach(context: AfterEach): Unit = restoreCaches()

  // 'name<><>2026/09/22(火) 12:00:00<>hello<>title\n' のShift_JISバイト列。
  // sjisBytes()ヘルパー(=検証対象のエンコーダ自身)で期待値を作ると、エンコーダにバグが
  // 入っても両辺が一緒にずれて常に一致してしまうため、ここでは実測して得た値をハードコードする。
  private val DatBoard1SjisBytes: List[Int] = List(
    110, 97, 109, 101, 60, 62, 60, 62, 50, 48, 50, 54, 47, 48, 57, 47, 50, 50,
    40, 137, 206, 41, 32, 49, 50, 58, 48, 48, 58, 48, 48, 60, 62, 104, 101, 108,
    108, 111, 60, 62, 116, 105, 116, 108, 101, 10
  )

  private def get(env: Env, path: String, headers: (String, String)*): Future[js.Dynamic] =
    fut[js.Dynamic](PublicRoutes.fetch(request(s"http://x$path", headers), env.worker, ctx))

  private def createThread(env: Env, bbs: String, key: String, title: String = "title", message: String = "hello", date: String = "2026/09/22(火) 12:00:00"): Future[Unit] =
    env.thread(s"$bbs/$key").addRes(s"name<><>$date<>$message<>$title", "1<>log",
      js.Dynamic.literal(bbs = bbs, key = key, title = title, resNum = 1, sage = false, maxThreads = 700, create = true)).toFuture.map(_ => ())

  private def updateThread(env: Env, bbs: String, key: String, title: String, resNum: Int): Future[Unit] =
    env.board(bbs).updateThread(js.Dynamic.literal(key = key, title = title, resNum = resNum), false, 700).toFuture

  private def putModifiedAt(env: Env, bbs: String, key: String, ms: Double): Future[Unit] =
    env.threadInstance(s"$bbs/$key").state.storage.put("modifiedAt", ms).toFuture

  test("旧ドメインの告知datはRange/304を使わず全文を返し、保存datと新ドメインは変えない") {
    val env = DurableTestKit.newEnv()
    for {
      _ <- createThread(env, "board1", "1800000200", message = " hello ")
      original <- env.thread("board1/1800000200").getDat().toFuture
      old <- fut[js.Dynamic](PublicRoutes.fetch(request("https://www.3chbbs.com/board1/dat/1800000200.dat", Seq("range" -> "bytes=5-", "if-modified-since" -> "Wed, 01 Jan 2100 00:00:00 GMT")), env.worker, ctx))
      oldBody <- sjisText(old)
      current <- fut[js.Dynamic](PublicRoutes.fetch(request("https://www.3ch.io/board1/dat/1800000200.dat", Nil), env.worker, ctx))
      currentBody <- sjisText(current)
      stored <- env.thread("board1/1800000200").getDat().toFuture
    } yield {
      assertEquals(status(old), 200)
      assertEquals(header(old, "content-range"), None)
      assertEquals(header(old, "accept-ranges"), Some("none"))
      assertEquals(oldBody.split("\n")(0).split("<>", -1).length, 5)
      assert(oldBody.contains(" hello<hr>"))
      assert(oldBody.contains("https://www.3ch.io/board1/"))
      assert(oldBody.contains("https://www.3ch.io/test/read.cgi/board1/1800000200/"))
      assert(oldBody.contains("３ちゃんねるは新ドメインへの移行中です。<br>https://www.3ch.io/"))
      assert(oldBody.contains("3ch https://github.com/MontereyLangit/3ch"))
      assert(oldBody.split("<>", -1)(3).endsWith(" "))
      assertEquals(currentBody, original)
      assertEquals(stored, original)
    }
  }

  test("旧ドメインのsubject先頭告知は有効なdatを持ち、新ドメインには追加しない") {
    val env = DurableTestKit.newEnv()
    for {
      old <- fut[js.Dynamic](PublicRoutes.fetch(request("https://www.3chbbs.com/board1/subject.txt", Nil), env.worker, ctx))
      oldBody <- sjisText(old)
      notice <- fut[js.Dynamic](PublicRoutes.fetch(request("https://www.3chbbs.com/board1/dat/0000000000.dat", Nil), env.worker, ctx))
      noticeBody <- sjisText(notice)
      current <- fut[js.Dynamic](PublicRoutes.fetch(request("https://www.3ch.io/board1/subject.txt", Nil), env.worker, ctx))
      currentBody <- sjisText(current)
      currentNotice <- fut[js.Dynamic](PublicRoutes.fetch(request("https://www.3ch.io/board1/dat/0000000000.dat", Nil), env.worker, ctx))
    } yield {
      assert(oldBody.startsWith("0000000000.dat<>"))
      assertEquals(status(notice), 200)
      assertEquals(noticeBody.trim.split("<>", -1).length, 5)
      assert(noticeBody.contains("BBSMENU: https://www.3ch.io/bbsmenu.html"))
      assert(noticeBody.contains("板URL: https://www.3ch.io/board1/"))
      assert(!noticeBody.contains("/0000000000/"))
      assertEquals(currentBody, "")
      assertEquals(status(currentNotice), 404)
      assertEquals(DomainMigration.subject("0000000000.dat<>existing (1)\n"), "0000000000.dat<>existing (1)\n")
    }
  }

  test("移行告知は第1レス本文末尾だけを変更し、2レス以降と他のフィールドを維持する") {
    val first = "name<>mail<>date<> first  <>title"
    val rest = "second<><>date<> second <>\nthird<><>date<> third <>\n"
    val original = first + "\n" + rest
    val annotated = DomainMigration.annotateDat(original, "sample", "1800000201")
    val fields = annotated.takeWhile(_ != '\n').split("<>", -1)
    assertEquals(fields.take(3).toList, List("name", "mail", "date"))
    assertEquals(fields(4), "title")
    assert(fields(3).startsWith(" first<hr>"))
    assert(fields(3).endsWith("  "))
    assertEquals(annotated.substring(annotated.indexOf('\n') + 1), rest)
    assertEquals(DomainMigration.annotateDat("invalid", "sample", "1800000201"), "invalid")
  }

  // ---------- 1. /{bbs}/dat/{key}.dat ----------

  test("dat: 通常取得は200・Shift_JISバイト列・cache-control/s-maxageヘッダ") {
    val env = DurableTestKit.newEnv()
    for {
      _ <- createThread(env, "board1", "1800000000")
      res <- get(env, "/board1/dat/1800000000.dat")
      body <- bytes(res)
    } yield {
      assertEquals(status(res), 200)
      assertEquals(header(res, "content-type"), Option("text/plain;charset=Shift_JIS"))
      assertEquals(header(res, "cache-control"), Option("no-store"))
      assertEquals(header(res, "s-maxage"), Option("1"))
      assertEquals(header(res, "accept-ranges"), Option("bytes"))
      assertEquals(body, DatBoard1SjisBytes)
      assertEquals(header(res, "content-length"), Option(DatBoard1SjisBytes.length.toString))
    }
  }

  test("dat: 削除済み(hidden)スレは404") {
    val env = DurableTestKit.newEnv()
    for {
      _ <- createThread(env, "board1", "1800000001")
      _ <- env.thread("board1/1800000001").moderate("delete", js.Dynamic.literal(bbs = "board1", key = "1800000001")).toFuture
      res <- get(env, "/board1/dat/1800000001.dat")
      body <- text(res)
    } yield {
      assertEquals(status(res), 404)
      assertEquals(header(res, "content-type"), Option("text/html;charset=UTF-8"))
      assert(body.contains("/board1/dat/1800000001.dat"))
    }
  }

  test("dat: 存在しないスレも404") {
    val env = DurableTestKit.newEnv()
    get(env, "/board1/dat/1999999999.dat").map(res => assertEquals(status(res), 404))
  }

  test("dat: Range bytes=N- は206でContent-Range/Content-Length/Accept-Ranges付き、本文バイトも指定範囲通り") {
    val env = DurableTestKit.newEnv()
    val total = DatBoard1SjisBytes.length
    for {
      _ <- createThread(env, "board1", "1800000002")
      res <- get(env, "/board1/dat/1800000002.dat", "range" -> "bytes=5-")
      body <- bytes(res)
    } yield {
      assertEquals(status(res), 206)
      assertEquals(header(res, "content-range"), Option(s"bytes 5-${total - 1}/$total"))
      assertEquals(header(res, "content-length"), Option((total - 5).toString))
      assertEquals(header(res, "accept-ranges"), Option("bytes"))
      // ヘッダだけでなく本文バイト自体も正しい範囲であることを検証する(ヘッダは合っていて
      // 本文の切り出しが間違っている移植ミスを検知するため)。
      assertEquals(body, DatBoard1SjisBytes.drop(5))
    }
  }

  test("dat: Range bytes=N-M は範囲だけの206、本文バイトも指定範囲通り") {
    val env = DurableTestKit.newEnv()
    for {
      _ <- createThread(env, "board1", "1800000003")
      res <- get(env, "/board1/dat/1800000003.dat", "range" -> "bytes=2-5")
      body <- bytes(res)
    } yield {
      assertEquals(status(res), 206)
      assertEquals(header(res, "content-range"), Option("bytes 2-5/46"))
      assertEquals(header(res, "content-length"), Option("4"))
      assertEquals(body, DatBoard1SjisBytes.slice(2, 6))
    }
  }

  test("dat: 範囲外のRangeは416でContent-Range: bytes */total") {
    val env = DurableTestKit.newEnv()
    for {
      _ <- createThread(env, "board1", "1800000004")
      res <- get(env, "/board1/dat/1800000004.dat", "range" -> "bytes=99999-")
    } yield {
      assertEquals(status(res), 416)
      assertEquals(header(res, "content-range"), Option("bytes */46"))
    }
  }

  test("dat: 書式不正なRangeは400・本文\"Bad Range Header\"") {
    val env = DurableTestKit.newEnv()
    for {
      _ <- createThread(env, "board1", "1800000006")
      res <- get(env, "/board1/dat/1800000006.dat", "range" -> "notarange")
      body <- text(res)
    } yield {
      assertEquals(status(res), 400)
      assertEquals(body, "Bad Range Header")
      // Response(text, {status}) の既定content-typeがそのまま出る（アプリ側では明示していない）。
      assertEquals(header(res, "content-type"), Option("text/plain;charset=UTF-8"))
    }
  }

  test("KB-23: dat: modifiedAtのミリ秒が0でなくても、Last-Modifiedを送り返せば304になる") {
    // modifiedAtのミリ秒を明示的に0でない値に固定する（Date.now() 依存のフレーキーさを避ける）。
    val env = DurableTestKit.newEnv()
    val fixedMs = js.Date.UTC(2024, 0, 1, 0, 0, 30, 500) // ミリ秒=500(≠0)に固定
    for {
      _ <- createThread(env, "board1", "1800000007")
      _ <- putModifiedAt(env, "board1", "1800000007", fixedMs)
      res1 <- get(env, "/board1/dat/1800000007.dat")
      lm = header(res1, "last-modified").get
      _ = assertEquals(lm, "Mon, 01 Jan 2024 00:00:30 GMT") // toUTCStringはミリ秒を切り捨てる
      resSame <- get(env, "/board1/dat/1800000007.dat", "if-modified-since" -> lm)
      _ = assertEquals(status(resSame), 304, "Last-Modifiedを秒に切り捨てて比較するので、同一値でも304になる")
      oneSecondLater = new js.Date(new js.Date(lm).getTime() + 1000).toUTCString()
      res2 <- get(env, "/board1/dat/1800000007.dat", "if-modified-since" -> oneSecondLater)
    } yield {
      assertEquals(status(res2), 304)
      assertEquals(header(res2, "last-modified"), Option(lm))
      assertEquals(header(res2, "content-type"), None)
    }
  }

  test("KB-23: dat: modifiedAtのミリ秒がちょうど0の場合は、同一値を送り返すと切り捨てによる誤差が無く304になる") {
    // KB-23の原因(秒精度の往復で誤差が生じる)が成立しない特殊ケース。modifiedAtのミリ秒が
    // ちょうど0であれば、toUTCString()の往復で情報が失われないため、同一値の送り返しでも
    // <=判定が成立し304になる。これも実際に起こりうる挙動として固定する。
    val env = DurableTestKit.newEnv()
    val fixedMs = js.Date.UTC(2024, 0, 1, 0, 0, 30, 0) // ミリ秒=0に固定
    for {
      _ <- createThread(env, "board1", "1800000107")
      _ <- putModifiedAt(env, "board1", "1800000107", fixedMs)
      res1 <- get(env, "/board1/dat/1800000107.dat")
      lm = header(res1, "last-modified").get
      _ = assertEquals(lm, "Mon, 01 Jan 2024 00:00:30 GMT")
      resSame <- get(env, "/board1/dat/1800000107.dat", "if-modified-since" -> lm)
    } yield assertEquals(status(resSame), 304, "ミリ秒がちょうど0の場合は誤差が無いため同一値でも304になる")
  }

  test("dat: Last-Modifiedは保存されているmodifiedAt由来で、現在時刻ではない（コメント訂正: dat中の最終レスの日付ではない）") {
    // publicFetch の dat ルートでは、Thread DOに保存された`modifiedAt`(Date.now()起源)が最優先で使われ、
    // datの最終行の日付文字列をパースする経路は`publicData.modifiedAt`が存在しない場合のフォールバックに過ぎない。
    val env = DurableTestKit.newEnv()
    val fixedPast = new js.Date("2020-01-01T00:00:00Z").getTime()
    for {
      _ <- createThread(env, "board1", "1800000008")
      _ <- putModifiedAt(env, "board1", "1800000008", fixedPast) // addRes直後のmodifiedAtを過去日時に差し替える
      res <- get(env, "/board1/dat/1800000008.dat")
    } yield assertEquals(header(res, "last-modified"), Option(new js.Date(fixedPast).toUTCString()))
  }

  test("dat: modifiedAtが未設定の場合のみ、datの最終行の日付文字列がLast-Modifiedのフォールバックとして使われる") {
    // dat ルート: publicData.modifiedAtが無い(falsy)場合に限り、dat最終行の日付欄をパースして
    // lastModifiedに使う。この経路が実際に機能することを固定する。
    val env = DurableTestKit.newEnv()
    for {
      _ <- createThread(env, "board1", "1800000108", date = "2021/03/04(木) 09:08:07")
      _ <- env.threadInstance("board1/1800000108").state.storage.delete("modifiedAt").toFuture
      res <- get(env, "/board1/dat/1800000108.dat")
    } yield assertEquals(header(res, "last-modified"), Option(new js.Date("2021/03/04(木) 09:08:07").toUTCString()))
  }

  test("dat: editRes後はRange/If-Modified-Sinceを無視して全文200を返す") {
    val env = DurableTestKit.newEnv()
    for {
      _ <- createThread(env, "board1", "1800000009")
      _ <- env.thread("board1/1800000009").moderate("editRes", js.Dynamic.literal(bbs = "board1", key = "1800000009", number = "1", name = "x", mail = "", message = "edited")).toFuture
      res <- get(env, "/board1/dat/1800000009.dat", "range" -> "bytes=0-3", "if-modified-since" -> new js.Date(js.Date.now() + 86400000).toUTCString())
    } yield assertEquals(status(res), 200, "編集済みスレはedited=trueのためrange/IMSが共に無効化される")
  }

  // ---------- 2. /{bbs}/subject.txt ----------

  test("KB-05: subject.txt: 200・Shift_JISバイト列・bareなmax-age/s-maxageヘッダ（Cache-Controlではない既知の不具合）") {
    val env = DurableTestKit.newEnv()
    for {
      _ <- updateThread(env, "board1", "1800000010", "AAA", 3)
      res <- get(env, "/board1/subject.txt")
      body <- bytes(res)
    } yield {
      assertEquals(status(res), 200)
      assertEquals(header(res, "content-type"), Option("text/plain;charset=Shift_JIS"))
      // 仕様書は "max-age=5, s-maxage=1" と記載しているが、実際にはCache-Controlヘッダではなく
      // 生の "max-age" / "s-maxage" という独自ヘッダ名で出力される（11章記載の既知の不具合）。
      assertEquals(header(res, "max-age"), Option("5"))
      assertEquals(header(res, "s-maxage"), Option("1"))
      assertEquals(header(res, "cache-control"), None)
      assertEquals(body, sjisBytes("1800000010.dat<>AAA (3)\n"))
    }
  }

  test("subject.txt: 板にスレが無ければ200・空文字列") {
    val env = DurableTestKit.newEnv()
    for {
      res <- get(env, "/emptyboard/subject.txt")
      body <- text(res)
    } yield {
      assertEquals(status(res), 200)
      assertEquals(body, "")
    }
  }

  test(
    """KB-06: subject.txt: 既知の不具合 — lastModifiedが常にnew Date()のため、
      |前回のLast-ModifiedをIf-Modified-Sinceに送っても304にならず200が返る（専ブラの定期巡回が軽減されない）""".stripMargin) {
    // グローバルDateをミリ秒≠0に固定して決定論的にする（ミリ秒が偶然0だと304になりフレーキーになるため）。
    withFixedTime(js.Date.UTC(2024, 0, 1, 0, 0, 30, 500)) {
      val env = DurableTestKit.newEnv()
      for {
        _ <- updateThread(env, "board1", "1800000011", "BBB", 1)
        res1 <- get(env, "/board1/subject.txt")
        lm = header(res1, "last-modified").get
        _ = assertEquals(lm, "Mon, 01 Jan 2024 00:00:30 GMT")
        res2 <- get(env, "/board1/subject.txt", "if-modified-since" -> lm)
      } yield assertEquals(status(res2), 200, "実際の巡回シナリオ（前回のLast-Modifiedをそのまま送る）では常に200")
    }
  }

  test("KB-06: subject.txt: new Date()のミリ秒がちょうど0の瞬間にリクエストが来た場合は、切り捨て誤差が無いため304になる") {
    // KB-06は「lastModifiedが常に現在時刻」であることそのものが原因ではなく、toUTCString()による
    // 秒精度への切り捨てが往復比較に誤差を生むことが原因。ミリ秒がちょうど0であれば誤差が生じないため304になる。
    withFixedTime(js.Date.UTC(2024, 0, 1, 0, 0, 30, 0)) {
      val env = DurableTestKit.newEnv()
      for {
        _ <- updateThread(env, "board1", "1800000111", "BBB", 1)
        res1 <- get(env, "/board1/subject.txt")
        lm = header(res1, "last-modified").get
        _ = assertEquals(lm, "Mon, 01 Jan 2024 00:00:30 GMT")
        res2 <- get(env, "/board1/subject.txt", "if-modified-since" -> lm)
      } yield assertEquals(status(res2), 304, "ミリ秒がちょうど0の場合は誤差が無いため304になる")
    }
  }

  test("subject.txt: タスク指示文の想定に反し、意図的に遠い未来のIf-Modified-Sinceを送ると実際には304になる") {
    // respondPlainTextの比較自体は `lastModified <= new Date(ifModifiedSinceHeader)` という素直な比較であり、
    // 本当に現在時刻より先の日付を送れば304になる。不具合として効いているのは「lastModifiedが常に現在時刻」
    // であるため前回値を送る通常の巡回では304にならない点で、それは上のテストで固定化した。
    val env = DurableTestKit.newEnv()
    val farFuture = new js.Date(js.Date.now() + 365d * 24 * 3600 * 1000).toUTCString()
    for {
      _ <- updateThread(env, "board1", "1800000012", "CCC", 1)
      res <- get(env, "/board1/subject.txt", "if-modified-since" -> farFuture)
    } yield assertEquals(status(res), 304)
  }

  // ---------- 2b. 全板共通スレ (docs/global-thread.md) ----------

  test("subject.txt: 板924の共通スレを 924 → 900 → 板固有 の順で先頭に載せる") {
    val env = DurableTestKit.newEnv()
    for {
      _ <- updateThread(env, "924", "9000000001", "open-old", 2)
      _ <- updateThread(env, "924", "9240000002", "cap-only", 5)
      _ <- updateThread(env, "924", "9000000003", "open-new", 1)
      _ <- updateThread(env, "board1", "1800000010", "own", 3)
      res <- get(env, "/board1/subject.txt")
      body <- sjisText(res)
      empty <- get(env, "/emptyboard/subject.txt")
      emptyBody <- sjisText(empty)
      own <- get(env, "/924/subject.txt")
      ownBody <- sjisText(own)
    } yield {
      assertEquals(status(res), 200)
      assertEquals(body, "9240000002.dat<>cap-only (5)\n9000000003.dat<>open-new (1)\n9000000001.dat<>open-old (2)\n1800000010.dat<>own (3)\n")
      // スレの無い板でも共通スレだけは載る
      assertEquals(emptyBody, "9240000002.dat<>cap-only (5)\n9000000003.dat<>open-new (1)\n9000000001.dat<>open-old (2)\n")
      // 板924自身は共通スレを並べ替えたものだけ
      assertEquals(ownBody, "9240000002.dat<>cap-only (5)\n9000000003.dat<>open-new (1)\n9000000001.dat<>open-old (2)\n")
    }
  }

  test("dat: 924/900始まりのキーはどの板のURLからでも板924のスレを返す") {
    val env = DurableTestKit.newEnv()
    for {
      _ <- createThread(env, "924", "9240000002", title = "cap-only")
      _ <- createThread(env, "924", "9000000003", title = "open")
      viaBoard <- get(env, "/board1/dat/9240000002.dat")
      viaBoardBody <- sjisText(viaBoard)
      viaOwn <- get(env, "/924/dat/9000000003.dat")
      viaOwnBody <- sjisText(viaOwn)
      viaOther <- get(env, "/emptyboard/dat/9000000003.dat")
      missing <- get(env, "/board1/dat/9240000009.dat")
    } yield {
      assertEquals(status(viaBoard), 200)
      assertEquals(viaBoardBody, "name<><>2026/09/22(火) 12:00:00<>hello<>cap-only\n")
      assertEquals(status(viaOwn), 200)
      assertEquals(viaOwnBody, "name<><>2026/09/22(火) 12:00:00<>hello<>open\n")
      assertEquals(status(viaOther), 200)
      assertEquals(status(missing), 404)
    }
  }

  // ---------- 3. /{bbs}/SETTING.TXT ----------

  test("SETTING.TXT: R2に無ければデフォルト設定で<DIR_NAME>が板名に置換される") {
    val env = DurableTestKit.newEnv()
    for {
      res <- get(env, "/board1/SETTING.TXT")
      body <- sjisText(res)
    } yield {
      assertEquals(status(res), 200)
      assertEquals(header(res, "content-type"), Option("text/plain;charset=Shift_JIS"))
      assert(body.split("\n", -1).contains("BBS_TITLE=board1＠3ch掲示板"))
    }
  }

  test("SETTING.TXT: R2にあればその内容を使い、<DIR_NAME>を置換する") {
    val env = DurableTestKit.newEnv()
    env.putR2("board2/SETTING.TXT", "BBS_TITLE=<DIR_NAME> custom\nFOO=bar\n")
    for {
      res <- get(env, "/board2/SETTING.TXT")
      body <- sjisText(res)
    } yield {
      val lines = body.split("\n", -1)
      assert(lines.contains("BBS_TITLE=board2 custom"))
      assert(lines.contains("FOO=bar"))
    }
  }

  // ---------- 4. /{bbs}/head.txt ----------

  test("head.txt: R2内容をShift_JISでそのまま返す") {
    val env = DurableTestKit.newEnv()
    env.putR2("board1/head.txt", "ローカルルールです")
    for {
      res <- get(env, "/board1/head.txt")
      body <- bytes(res)
    } yield {
      assertEquals(status(res), 200)
      assertEquals(header(res, "content-type"), Option("text/plain;charset=Shift_JIS"))
      assertEquals(body, sjisBytes("ローカルルールです"))
    }
  }

  test("head.txt: R2に無ければ空文字列で200") {
    val env = DurableTestKit.newEnv()
    for {
      res <- get(env, "/noheadboard/head.txt")
      body <- bytes(res)
    } yield {
      assertEquals(status(res), 200)
      assertEquals(body, List.empty[Int])
    }
  }

  // ---------- 5. 3chiko.html統合 ----------

  private def assert3chiko(res: js.Dynamic): Future[Unit] = {
    assertEquals(status(res), 200)
    text(res).map(html => assert(html.contains("<!-- 3chiko.html -->")))
  }

  test("トップページ /: Static Assets(ASSETS binding)経由で3chiko.htmlを返す") {
    get(DurableTestKit.newEnv(), "/").flatMap(assert3chiko)
  }

  test("read.cgi /test/read.cgi/{bbs}/{key}/: 3chiko.htmlを返す") {
    get(DurableTestKit.newEnv(), "/test/read.cgi/board1/1800000000/").flatMap(assert3chiko)
  }

  for (path <- List("/board1/kako/", "/board1/kako/1700")) {
    test(s"過去ログ倉庫 $path: 3chiko.htmlを返す（subject.txt系APIとは別ルート）") {
      get(DurableTestKit.newEnv(), path).flatMap(assert3chiko)
    }
  }

  // ---------- 6. /bbsmenu.html ----------

  test("bbsmenu.html: R2にbbsmenu.htmlがあればcache-control:no-storeで直接返す") {
    val env = DurableTestKit.newEnv()
    env.putR2("bbsmenu.html", "<html>custom menu</html>")
    for {
      res <- get(env, "/bbsmenu.html")
      body <- bytes(res)
    } yield {
      assertEquals(status(res), 200)
      assertEquals(header(res, "cache-control"), Option("no-store"))
      assertEquals(body, sjisBytes("<html>custom menu</html>"))
    }
  }

  test("bbsmenu.html: bbsmenu.html無し・headline.txtありならheadlineから生成し、bareなmax-age/s-maxage") {
    val env = DurableTestKit.newEnv()
    env.putR2("headline.txt", "1700000000\nboard1<>Board One<>1700000000<>ThreadTitle<>lastmsg\n")
    for {
      res <- get(env, "/bbsmenu.html")
      html <- sjisText(res)
    } yield {
      assertEquals(status(res), 200)
      assertEquals(header(res, "max-age"), Option("60"))
      assertEquals(header(res, "s-maxage"), Option("60"))
      assertEquals(header(res, "cache-control"), None)
      assert(html.contains("Board One"))
    }
  }

  test("bbsmenu.html: bbsmenu.htmlもheadline.txtも無ければ404") {
    get(DurableTestKit.newEnv(), "/bbsmenu.html").map(res => assertEquals(status(res), 404))
  }

  // ---------- 7. /headline/subject.txt ----------

  test("headline/subject.txt: R2のheadline.txtからUTF-8で整形して返す（cache-control:no-store）") {
    val env = DurableTestKit.newEnv()
    env.putR2("headline.txt", "1700000000\nboard1<>Board One<>1700000000<>ThreadTitle<>lastmsg\n")
    for {
      res <- get(env, "/headline/subject.txt")
      body <- bytes(res)
    } yield {
      assertEquals(status(res), 200)
      assertEquals(header(res, "content-type"), Option("text/plain;charset=UTF-8"))
      assertEquals(header(res, "cache-control"), Option("no-store"))
      assertEquals(body, utf8Bytes("board1<>Board One<>1700000000<>ThreadTitle<>lastmsg\n"))
    }
  }

  test("headline/subject.txt: headline.txtが無ければ404") {
    get(DurableTestKit.newEnv(), "/headline/subject.txt").map(res => assertEquals(status(res), 404))
  }

  // ---------- 8. /{bbs}/kako/... ----------

  test("kako/{年}/subject.txt: R2の年別ログをUTF-8で返し、削除済みキーは除外・cache-control:no-store") {
    val env = DurableTestKit.newEnv()
    env.putR2("board1/thread/1700/subject.txt", "1700000000\n1700000001<>ThreadA<>name<>id<>date<>msg\n1700000002<>ThreadB<>name<>id<>date<>msg\n")
    for {
      res1 <- get(env, "/board1/kako/1700/subject.txt")
      body1 <- text(res1)
      _ = assertEquals(status(res1), 200)
      _ = assertEquals(header(res1, "content-type"), Option("text/plain;charset=UTF-8"))
      _ = assertEquals(header(res1, "cache-control"), Option("no-store"))
      _ = assertEquals(body1, "1700000001<>ThreadA<>name<>id<>date<>msg\n1700000002<>ThreadB<>name<>id<>date<>msg\n")
      _ <- env.board("board1").removeThread("1700000001").toFuture
      res2 <- get(env, "/board1/kako/1700/subject.txt")
      body2 <- text(res2)
    } yield assertEquals(body2, "1700000002<>ThreadB<>name<>id<>date<>msg\n")
  }

  test("kako/{年}/subject.txt: R2に無ければ404") {
    get(DurableTestKit.newEnv(), "/board1/kako/1800/subject.txt").map(res => assertEquals(status(res), 404))
  }

  test("kako/subject.txt: 年一覧はbareなmax-age=86400,s-maxage=86400（Cache-Controlではない）") {
    val env = DurableTestKit.newEnv()
    env.putR2("board1/thread/subject.txt", "1700000000\n1700<>1700000000\n")
    for {
      res <- get(env, "/board1/kako/subject.txt")
      body <- text(res)
    } yield {
      assertEquals(status(res), 200)
      assertEquals(header(res, "content-type"), Option("text/plain;charset=UTF-8"))
      assertEquals(header(res, "max-age"), Option("86400"))
      assertEquals(header(res, "s-maxage"), Option("86400"))
      assertEquals(header(res, "cache-control"), None)
      assertEquals(body, "1700<>1700000000\n")
    }
  }

  // ---------- 9. /auth.js ----------

  test("auth.js: 固定スクリプトをUTF-8 application/javascriptで返す") {
    for {
      res <- get(DurableTestKit.newEnv(), "/auth.js")
      body <- bytes(res)
    } yield {
      assertEquals(status(res), 200)
      assertEquals(header(res, "content-type"), Option("application/javascript;charset=UTF-8"))
      assertEquals(body, utf8Bytes("/* test */"))
    }
  }

  // ---------- 10. /test/sakubbs.cgi/25 ----------

  // v6 では意図的に削除した（v5 は固定文字列 "sakubbs.cgi v25" を返していた）。
  test("sakubbs.cgi/25: 意図的に削除済みで404になる") {
    get(DurableTestKit.newEnv(), "/test/sakubbs.cgi/25").map(res => assertEquals(status(res), 404))
  }

  // ---------- 11. 未知のパス ----------

  test("未知のパス: 404レスポンス形状（パスをエスケープして本文に含む）") {
    for {
      res <- get(DurableTestKit.newEnv(), "/unknown/path/here")
      body <- text(res)
    } yield {
      assertEquals(status(res), 404)
      assertEquals(header(res, "content-type"), Option("text/html;charset=UTF-8"))
      assert(body.contains("<title>Error 404</title>"))
      assert(body.contains("/unknown/path/here"))
    }
  }

  // ---------- 12. POST /test/bbs.cgi , /test/subbbs.cgi のルーティング ----------

  test("POST /test/bbs.cgi と /test/subbbs.cgi はどちらも投稿エントリポイントに到達する") {
    val env = DurableTestKit.newEnv()
    def body(): js.Any = js.Dynamic.newInstance(global.URLSearchParams)(js.Dynamic.literal(
      bbs = "board1", key = "", MESSAGE = "hi", FROM = "a", mail = "", subject = "t", time = "1", submit = "書き込む"
    )).asInstanceOf[js.Any]
    def post(path: String): Future[js.Dynamic] =
      fut[js.Dynamic](PublicRoutes.fetch(request(s"http://x$path", method = "POST", body = body()), env.worker, ctx))
    for {
      res1 <- post("/test/bbs.cgi")
      _ = assertEquals(status(res1), 200)
      _ = assertEquals(header(res1, "content-type"), Option("text/html;charset=UTF-8"))
      res2 <- post("/test/subbbs.cgi")
    } yield {
      assertEquals(status(res2), 200)
      assertEquals(header(res2, "content-type"), Option("text/html;charset=UTF-8"))
    }
  }
}
