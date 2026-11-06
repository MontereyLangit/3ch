package io._3ch.bbs.application.posting

import io._3ch.bbs.application.posting.ports.{ArchiveIndexFile, ArchiveIndexStore, CapStore, Clock, Hasher, PostingUserSource, Random, RuleStore, SettingsStore, StoredIndex, ThreadSnapshot, ThreadStore}
import io._3ch.bbs.domain.posting.{BrokenLogKind, FailureClock, FailureSite, ModerationDenial, PostingError, PostingStage, PostingUser, ThreadOwner, ThreadPosting, ThreadWriteResult}
import io._3ch.bbs.domain.posting.moderation.{ModerationRuleFile, ModerationRuleSet, ModerationRuleSource, ThreadCreationRejection, ThreadCreationRateLimit}
import io._3ch.bbs.external.web.TextEncoder
import io._3ch.common.domain.{BbsSettings, BoardId}
import io._3ch.common.domain.posting.CapHandle
import munit.FunSuite

import scala.collection.immutable.ListMap
import scala.collection.mutable
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.typedarray.Uint8Array

/** メモリ上のポートで、段の順序・拒否後に後段を呼ばないこと・copy で埋まる内容を確かめる */
class PostingServiceSuite extends FunSuite {
  private val Now = 1700000000000d
  private val ExistingDat = "名前<><>日付<> 本文 <>既存スレ\n"

  /** 呼ばれた順に記録するメモリ上のポート */
  private final class Fake(
    boardSettings: String = "BBS_TITLE=看板\n",
    threadDat: String = ExistingDat,
    threadSettings: String = "",
    owner: Option[ThreadOwner] = None,
    rules: Map[ModerationRuleFile, String] = Map.empty,
    caps: Map[String, String] = Map.empty,
    user: Option[PostingUser] = None,
    roll: Double = 0.5,
    write: ThreadPosting => Future[ThreadWriteResult] = posting => Future.successful(ThreadWriteResult.Written(if (posting.create) 1 else 2)),
    indexWritten: Boolean = true
  ) {
    val calls: mutable.Buffer[String] = mutable.Buffer.empty
    val postings: mutable.Buffer[(String, String, ThreadPosting)] = mutable.Buffer.empty
    val writes: mutable.Buffer[(String, String)] = mutable.Buffer.empty
    private def called[A](name: String)(value: => A): Future[A] = { calls += name; Future(value) }

    val ports: PostingPorts = PostingPorts(
      threads = new ThreadStore {
        def datBeforeCreation(board: BoardId, key: String): Future[String] = called(s"threads.datBeforeCreation ${board.value}/$key")("")
        def readForReply(board: BoardId, key: String): Future[ThreadSnapshot] =
          called(s"threads.readForReply ${board.value}/$key")(ThreadSnapshot(threadDat, BbsSettings.parse(threadSettings, "<>"), () => { calls += "owner"; owner }))
        def addRes(board: BoardId, key: String, datLine: String, logLine: String, posting: ThreadPosting): Future[ThreadWriteResult] = {
          calls += s"threads.addRes ${board.value}/$key"
          postings += ((datLine, logLine, posting))
          write(posting)
        }
      },
      settings = new SettingsStore {
        def boardSettings(board: BoardId): Future[BbsSettings] = called(s"settings ${board.value}")(BbsSettings.create(Fake.this.boardSettings, board))
      },
      rules = new RuleStore {
        def load(file: ModerationRuleFile): Future[ModerationRuleSource] = called(s"rules ${file.path}") {
          rules.get(file).fold(ModerationRuleSource.Missing)(text => ModerationRuleSource.Loaded(ModerationRuleSet.parse(text).toOption.get))
        }
      },
      caps = new CapStore {
        def find(key: String): Future[Option[CapHandle]] = called(s"caps $key")(caps.get(key).flatMap(CapHandle.fromStored))
      },
      archive = new ArchiveIndexStore {
        def read(file: ArchiveIndexFile): Future[Option[StoredIndex]] = called(s"archive.read $file")(None)
        def write(file: ArchiveIndexFile, value: String, etag: Option[String]): Future[Boolean] = {
          writes += (file.toString -> value)
          called(s"archive.write $file")(indexWritten)
        }
        def readHeadline(): Future[Option[StoredIndex]] = called("archive.readHeadline")(None)
        def writeHeadline(value: String, etag: Option[String]): Future[Unit] = {
          writes += ("headline" -> value)
          called("archive.writeHeadline")(())
        }
      },
      users = new PostingUserSource {
        def current(): Future[Option[PostingUser]] = called("users")(user)
      },
      hasher = new Hasher {
        def md5(value: String): String = s"m($value)"
        def sha1Base64(value: String): Future[String] = Future.successful(s"s1($value)")
        def sha256Hex(value: String): Future[String] = Future.successful(s"s2($value)")
      },
      clock = new Clock {
        def nowMillis(): Double = Now
        def datDate(unixtime: Double): String = s"date($unixtime)"
      },
      random = new Random {
        def next(): Double = { calls += "random"; roll }
      }
    )

    def post(fields: (String, String)*)(headers: PostingRequest => PostingRequest = identity): Future[PostingOutcome] = {
      val values = ListMap("FROM" -> "", "mail" -> "", "MESSAGE" -> "本文", "subject" -> "題名", "bbs" -> "sample", "key" -> "", "time" -> "1") ++ fields
      val text = (values.toSeq :+ ("submit" -> "書き込む")).map((k, v) => s"$k=${js.URIUtils.encodeURIComponent(v)}").mkString("&")
      val request = PostingRequest(
        method = "POST", contentType = "application/x-www-form-urlencoded", referer = "https://www.3ch.io/", userAgent = "Mozilla/5.0 (Test)",
        secChUa = "", secChUaFullVersionList = None, accept = "", acceptLanguage = "", acceptEncoding = "", deviceInformation = "", clientIp = "",
        connectingIp = Some("192.0.2.1"), forwardedFor = "", forwarded = "", via = "", cookie = "",
        asn = Some("2516"), country = Some("JP"), region = Some("Tokyo"), city = Some("Chiyoda"),
        body = () => { calls += "body"; Future.successful(new TextEncoder().encode(text)) }
      )
      new PostingService(ports, "salt").post(headers(request))
    }
  }

  private def reply(fields: (String, String)*): Seq[(String, String)] = Seq("subject" -> "", "key" -> "1699990000") ++ fields

  private def rejected(outcome: PostingOutcome): PostingOutcome.Rejected = outcome match {
    case r: PostingOutcome.Rejected => r
    case other => fail(s"拒否されなかった: $other")
  }

  private val salt = "m(salt)"
  private val pon = s"PON=s1(s2(m(192.0.2.1)$salt)); Max-Age=63072000; path=/; Secure; HttpOnly"

  test("スレ立ては段の順にポートを呼び、確定したレス番号と Set-Cookie を返す") {
    val fake = Fake()
    fake.post()().map { outcome =>
      assertEquals(fake.calls.toList, List(
        "body", "settings sample", "threads.datBeforeCreation sample/1700000000", "users", "rules deny.cgi", "rules ngwords.cgi",
        "threads.addRes sample/1700000000", "archive.read ThreadIndex(sample,1700000000)", "archive.read BandList(sample)",
        "archive.write BandList(sample)", "archive.write ThreadIndex(sample,1700000000)", "archive.readHeadline", "archive.writeHeadline"
      ))
      outcome match {
        case PostingOutcome.Posted(context, headers) =>
          assertEquals(headers, Seq("Set-Cookie" -> pon))
          assertEquals(context.number, 1)
          assertEquals(context.title, "題名")
          assertEquals(context.time, "1700000000000")
          assertEquals(context.unixtime, "1700000000")
          assertEquals(context.form.key, "1700000000")
          assertEquals(context.sage, false)
          assertEquals(context.settings.value.toList, List("BBS_TITLE" -> "看板", "BBS_SHIKIBETSU" -> "off"))
          val shikibetsu = "JP.s1.s1 s1-m(Mozilla/5.0 (T"
          assertEquals(context.shikibetsu, shikibetsu)
          assertEquals(context.form.name, s"名無しさん＠３ちゃんねる </b>($shikibetsu)<b>")
          assertEquals(context.identity.host, "192.0.2.1")
          assertEquals(context.identity.salt, salt)
        case other => fail(s"書けなかった: $other")
      }
    }
  }

  test("スレ立ての addRes には dat・log と、初期スレ設定・スレ立て規制・スレ主を渡す") {
    val fake = Fake()
    fake.post()().map { _ =>
      val (dat, log, posting) = fake.postings.head
      assert(dat.startsWith("名無しさん＠３ちゃんねる </b>(JP."), dat)
      assert(dat.endsWith("<>date(1700000000)<> 本文 <>題名"), dat)
      assert(log.startsWith("1<>UID: IP:192.0.2.1 AS2516-JP,Tokyo,Chiyoda"), log)
      assertEquals(posting.create, true)
      assertEquals(posting.title, Some("題名"))
      assertEquals(posting.maxThreads, Some(700d))
      assertEquals(posting.initialSettings, Some("BBS_TITLE=看板<>BBS_SHIKIBETSU=off<>"))
      assertEquals(posting.creationLimit.map(_.request.identity.ip), Some(s"s2(192.0.2.1:$salt)"))
      assertEquals(posting.creationLimit.map(_.request.identity.networkGroup), Some(s"s2(AS:2516:JP:Tokyo:$salt)"))
      assertEquals(posting.owner.map(_.ip), Some(s"s2(192.0.2.1:$salt)"))
      assertEquals(fake.writes.map(_._1).toList, List("BandList(sample)", "ThreadIndex(sample,1700000000)", "headline"))
      assert(fake.writes.last._2.startsWith("1700000000\nsample<>看板<>1700000000<>1700000000<>本文\n"), fake.writes.last._2)
    }
  }

  test("返信はスレ設定を板設定に重ね、スレ主の照合をしてから書き、過去ログ目次は触らない") {
    val owner = ThreadOwner("other", "", "")
    val fake = Fake(threadSettings = "BBS_TITLE=スレ<>X=1<>", owner = Some(owner))
    fake.post(reply()*)().map { outcome =>
      assertEquals(fake.calls.toList, List(
        "body", "settings sample", "threads.readForReply sample/1699990000", "users", "rules deny.cgi", "rules ngwords.cgi", "owner",
        "threads.addRes sample/1699990000", "archive.readHeadline", "archive.writeHeadline"
      ))
      val context = outcome.context
      assertEquals(context.number, 2)
      assertEquals(context.title, "既存スレ")
      assertEquals(context.settings.value.toList, List("BBS_TITLE" -> "スレ", "X" -> "1", "BBS_SHIKIBETSU" -> "off"))
      val posting = fake.postings.head._3
      assertEquals((posting.create, posting.initialSettings, posting.owner, posting.creationLimit), (false, None, None, None))
    }
  }

  test("返信のスレ主には (主) を付ける") {
    val fake = Fake(owner = Some(ThreadOwner(s"s2(192.0.2.1:$salt)", "", "")))
    fake.post(reply()*)().map(outcome => assert(outcome.context.form.name.endsWith(" (主)"), outcome.context.form.name))
  }

  test("sage の判定は BBS_LIVE のときだけ乱数を読み、sage なら headline を書かない") {
    val live = Fake(boardSettings = "BBS_LIVE=checked\n", roll = 0.5)
    val plain = Fake()
    for {
      liveOutcome <- live.post(reply()*)()
      plainOutcome <- plain.post(reply()*)()
    } yield {
      assertEquals(liveOutcome.context.sage, true)
      assert(live.calls.contains("random"))
      assert(!live.calls.exists(_.startsWith("archive")))
      assertEquals(live.postings.head._3.sage, true)
      assertEquals(plainOutcome.context.sage, false)
      assert(!plain.calls.contains("random"))
    }
  }

  test("ヘッダの検査で拒否すれば本文を読まず、ホストは検査前の値、Set-Cookie は付けない") {
    val fake = Fake()
    fake.post()(_.copy(method = "GET", forwardedFor = "203.0.113.1")).map { outcome =>
      val r = rejected(outcome)
      assertEquals(r.error, PostingError.MethodNotPost)
      assertEquals(r.headers, Nil)
      assertEquals(r.context.identity.host, "192.0.2.1")
      assertEquals(fake.calls.toList, Nil)
    }
  }

  test("閉鎖中の板はスレを読まず、識別子・規制・書き込みの段に進まない") {
    val fake = Fake(boardSettings = "BBS_HEISA=checked\n")
    fake.post(reply()*)().map { outcome =>
      assertEquals(rejected(outcome).error, PostingError.Closed)
      assertEquals(outcome.headers, Nil)
      assertEquals(fake.calls.toList, List("body", "settings sample"))
    }
  }

  test("スレの検査はスレ設定を重ねる前の拒否を優先し、満杯は閉鎖より先") {
    val full = Fake(threadDat = ExistingDat * 999, threadSettings = "BBS_HEISA=checked<>")
    val broken = Fake(threadDat = "\n")
    for {
      fullOutcome <- full.post(reply()*)()
      brokenOutcome <- broken.post(reply()*)()
    } yield {
      assertEquals(rejected(fullOutcome).error, PostingError.ThreadFull)
      assertEquals(rejected(brokenOutcome).error, PostingError.BrokenLog(BrokenLogKind.Empty))
      assert(!full.calls.contains("owner"))
      assert(!full.calls.contains("users"))
    }
  }

  test("規制で拒否すれば書き込まず、mark 済みのフォームと Set-Cookie を返す") {
    val fake = Fake(rules = Map(ModerationRuleFile.NgWords -> "<><>本文<>deny\n"))
    fake.post()().map { outcome =>
      val r = rejected(outcome)
      assertEquals(r.error, PostingError.Moderated(ModerationDenial.NgWord))
      assertEquals(r.headers, Seq("Set-Cookie" -> pon))
      assert(!fake.calls.exists(_.startsWith("threads.addRes")))
    }
  }

  test("キャップは照合した表示名を名前にし、識別子を ★ にしてスレ主もスレ立て規制も付けない") {
    val fake = Fake(caps = Map("pass" -> "管理人"))
    fake.post("FROM" -> "名前", "mail" -> "#!pass")().map { outcome =>
      assertEquals(fake.calls.head, "body")
      assertEquals(fake.calls(1), "caps pass")
      assertEquals(outcome.context.cap, Some(CapHandle("管理人", strong = false)))
      assertEquals(outcome.context.form.name, "名前＠管理人 ★ </b>(★)<b>")
      val posting = fake.postings.head._3
      assertEquals((posting.owner, posting.creationLimit), (None, None))
    }
  }

  test("スレ立て規制の拒否は ThreadCreation にし、過去ログ目次を書かない") {
    val fake = Fake(write = _ => Future.successful(ThreadWriteResult.CreationLimited(ThreadCreationRejection.Narrow(ThreadCreationRateLimit.Reason.Ip))))
    fake.post()().map { outcome =>
      assertEquals(rejected(outcome).error, PostingError.ThreadCreation(ThreadCreationRejection.Narrow(ThreadCreationRateLimit.Reason.Ip)))
      assert(!fake.calls.exists(_.startsWith("archive")))
    }
  }

  test("書き込みの失敗は内部エラーにし、受付時刻と書き込み前のレス番号、空白を入れた本文を残す") {
    val fake = Fake(write = _ => Future.failed(js.JavaScriptException(new js.Error("down"))))
    fake.post("MESSAGE" -> "a\nb")().map { outcome =>
      val r = rejected(outcome)
      assertEquals(r.error, PostingError.Internal(PostingStage.ThreadWrite, FailureSite(FailureClock.Unixtime, "1700000000000", "sample", "1700000000", "1"), "Error: down"))
      assertEquals(r.context.form.message, "a <br> b")
    }
  }

  test("過去ログ目次の競合は時刻を undefined にして拒否する（KB-39）") {
    val fake = Fake(indexWritten = false)
    fake.post()().map { outcome =>
      assertEquals(rejected(outcome).error, PostingError.ArchiveConflict("kakologList", FailureSite(FailureClock.Unixtime, "undefined", "sample", "1700000000", "1")))
      assert(!fake.calls.contains("archive.readHeadline"))
    }
  }

  test("!id は解決した利用者を名前欄に出す") {
    val fake = Fake(user = Some(PostingUser("account", "alice")))
    fake.post("FROM" -> "名前!id")().map { outcome =>
      assert(outcome.context.form.name.startsWith("名前 </b>(@alice)<b> </b>("), outcome.context.form.name)
      assertEquals(fake.postings.head._3.owner.map(_.account), Some(s"s2(account:$salt)"))
    }
  }
}
