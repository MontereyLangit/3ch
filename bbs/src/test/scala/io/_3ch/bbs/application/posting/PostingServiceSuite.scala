package io._3ch.bbs.application.posting

import io._3ch.bbs.application.posting.ports.{ArchiveIndexFile, ArchiveIndexStore, CapStore, Clock, Hasher, PostRateLog, PostRateObservation, PostingAccount, PostingAccounts, PostingUserSource, Random, RuleStore, SettingsStore, StoredIndex, ThreadSnapshot, ThreadStore}
import io._3ch.bbs.domain.posting.{BrokenLogKind, FailureClock, FailureSite, ModerationDenial, PostingError, PostingStage, PostingUser, ThreadOwner, ThreadPosting, ThreadWriteResult}
import io._3ch.bbs.domain.posting.moderation.{ModerationRuleFile, ModerationRuleSet, ModerationRuleSource, PostRateFinding, PostRateIdentity, PostRateLevel, PostRateRejection, ThreadCreationRejection, ThreadCreationRateLimit}
import io._3ch.bbs.handler.PostingText
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
    authSource: Option[PostingAccounts] = None,
    roll: Double = 0.5,
    write: ThreadPosting => Future[ThreadWriteResult] = posting => Future.successful(ThreadWriteResult.Written(if (posting.create) 1 else 2)),
    indexWritten: Boolean = true,
    record: PostRateObservation => Unit = _ => ()
  ) {
    val calls: mutable.Buffer[String] = mutable.Buffer.empty
    val postings: mutable.Buffer[(String, String, ThreadPosting)] = mutable.Buffer.empty
    val writes: mutable.Buffer[(String, String)] = mutable.Buffer.empty
    val md5Inputs: mutable.Buffer[String] = mutable.Buffer.empty
    val observations: mutable.Buffer[PostRateObservation] = mutable.Buffer.empty
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
      accounts = authSource,
      hasher = new Hasher {
        def md5(value: String): String = { md5Inputs += value; s"m($value)" }
        def sha1Base64(value: String): Future[String] = Future.successful(s"s1($value)")
        def sha256Hex(value: String): Future[String] = Future.successful(s"s2($value)")
      },
      clock = new Clock {
        def nowMillis(): Double = Now
        def datDate(unixtime: Double): String = s"date($unixtime)"
      },
      random = new Random {
        def next(): Double = { calls += "random"; roll }
      },
      rateLog = new PostRateLog {
        def record(observation: PostRateObservation): Unit = {
          observations += observation
          Fake.this.record(observation)
        }
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

  private final class AuthSource(user: Option[PostingUser] = None, failRecord: Boolean = false, device: String = "p012345678901234567",
    refreshed: Option[String] = None) extends PostingAccounts {
    var records = 0
    var recordFailures = 0
    val received: mutable.Buffer[(io._3ch.common.domain.posting.MailCommands, Option[String])] = mutable.Buffer.empty
    def authenticate(commands: io._3ch.common.domain.posting.MailCommands, capKey: Option[String], request: PostingRequest, now: Double) = {
      received += (commands -> capKey)
      Future.successful(Right(PostingAccount(user, 1, device, sessionCookie = Some("auth=present"))))
    }
    def posted(context: io._3ch.bbs.domain.posting.PostingContext, request: PostingRequest, account: PostingAccount): Future[Option[String]] = {
      records += 1
      if (failRecord) Future.failed(new IllegalStateException("unavailable")) else Future.successful(refreshed)
    }
    def recordFailed(error: Throwable): Unit = { recordFailures += 1 }
  }

  test("認証未設定の既存利用者ポートは UID ログの互換性を保つ") {
    val fake = Fake(user = Some(PostingUser("account-a", "user_a")))
    fake.post()().map { _ =>
      assert(fake.postings.head._2.contains("UID: IP:"))
      assert(!fake.postings.head._2.contains("UID:user_a"))
    }
  }

  test("認証設定済みの Web 投稿は匿名なら書き込む前に拒否する") {
    val source = new AuthSource()
    val fake = Fake(authSource = Some(source))
    fake.post()().map { outcome =>
      assertEquals(rejected(outcome).error, PostingError.Authentication(io._3ch.bbs.domain.account.AuthenticationFailure.LoginRequired))
      assertEquals(fake.postings.size, 0)
      assertEquals(source.records, 0)
    }
  }

  test("専ブラ匿名投稿は !auth が無ければ認証設定後も書き込める") {
    val source = new AuthSource()
    val fake = Fake(authSource = Some(source))
    fake.post()(r => r.copy(userAgent = "Monazilla/1.00 Test")).map(outcome => assert(outcome.isInstanceOf[PostingOutcome.Posted]))
  }

  test("専ブラの !auth は匿名なら認証を求める") {
    val fake = Fake(authSource = Some(new AuthSource()))
    fake.post("mail" -> "sage!auth")(r => r.copy(userAgent = "Monazilla/1.00 Test")).map { outcome =>
      assertEquals(rejected(outcome).error, PostingError.Authentication(io._3ch.bbs.domain.account.AuthenticationFailure.LoginRequired))
      assertEquals(outcome.context.form.mail, "sage")
    }
  }

  test("認証設定済みのキャップは空白を含むパスワードを従来どおり読み、メール欄からその部分だけを除く") {
    val source = new AuthSource(Some(PostingUser("account-a", "user_a")))
    val fake = Fake(caps = Map("pass word" -> "管理人"), authSource = Some(source))
    fake.post(reply("mail" -> "!#abcdefghij234567abcd sage#!pass word")*)().map { outcome =>
      assertEquals(outcome.context.cap, Some(CapHandle("管理人", strong = false)))
      assertEquals(source.received.map(_._2).toList, List(Option("pass word")))
      assertEquals(source.received.head._1.token, Some("abcdefghij234567abcd"))
      assertEquals(outcome.context.form.mail, " sage")
      assertEquals(outcome.context.sage, true)
    }
  }

  test("認証済み投稿は UID と端末を記録し PON と認証 Cookie を併記する") {
    val source = new AuthSource(Some(PostingUser("account-a", "user_a")))
    val fake = Fake(authSource = Some(source))
    fake.post()().map { outcome =>
      assert(fake.postings.head._2.contains("UID:user_a"))
      assert(fake.postings.head._2.contains("Device:p012345678901234567"))
      assertEquals(source.records, 1)
      assertEquals(outcome.headers.count(_._1 == "Set-Cookie"), 2)
    }
  }

  test("投稿後に発行し直した Cookie は認証時の Cookie と差し替え、Set-Cookie を重ねない") {
    val source = new AuthSource(Some(PostingUser("account-a", "user_a")), refreshed = Some("auth=refreshed"))
    val fake = Fake(authSource = Some(source))
    fake.post()().map(outcome => assertEquals(outcome.headers.filter(_._1 == "Set-Cookie").map(_._2), Seq(pon, "auth=refreshed")))
  }

  test("端末ハッシュの無い認証投稿と匿名投稿は従来の端末情報を記録する") {
    val source = new AuthSource(device = "")
    val fake = Fake(authSource = Some(source))
    fake.post()(r => r.copy(userAgent = "Monazilla/1.00 Test", deviceInformation = "legacy-device")).map { _ =>
      assert(fake.postings.head._2.contains("Device:legacy-device"), fake.postings.head._2)
    }
  }

  test("dat 保存後のアカウント記録失敗でも成功応答と索引更新を続ける") {
    val source = new AuthSource(Some(PostingUser("account-a", "user_a")), failRecord = true)
    val fake = Fake(authSource = Some(source))
    fake.post()().map { outcome =>
      assert(outcome.isInstanceOf[PostingOutcome.Posted])
      assertEquals(source.recordFailures, 1)
      assert(fake.writes.exists(_._1 == "headline"))
    }
  }

  test("吸い込み投稿はアカウントの期限と成功件数を更新しない") {
    val source = new AuthSource(Some(PostingUser("account-a", "user_a")))
    val fake = Fake(authSource = Some(source), write = _ => Future.successful(ThreadWriteResult.Swallowed(2)))
    fake.post()().map { outcome =>
      assert(outcome.isInstanceOf[PostingOutcome.Swallowed])
      assertEquals(source.records, 0)
    }
  }

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

  test("巨大な指数の文字数上限は Infinity として読み、スレ立てと返信を受け付ける") {
    val settings = "BBS_SUBJECT_COUNT=1e2147483648\nBBS_NAME_COUNT=1e2147483648\nBBS_MESSAGE_COUNT=1e2147483648\n"
    for {
      created <- Fake(boardSettings = settings).post("subject" -> "題" * 100, "FROM" -> "名" * 100)()
      replied <- Fake(boardSettings = settings).post(reply("FROM" -> "名" * 100, "MESSAGE" -> "文" * 5000)*)()
    } yield {
      assert(created.isInstanceOf[PostingOutcome.Posted], created.toString)
      assert(replied.isInstanceOf[PostingOutcome.Posted], replied.toString)
    }
  }

  test("BBS_ID_RESET の負のゼロと負数のアンダーフローは同じ識別子の日付窓になる") {
    def seed(reset: String): Future[Seq[String]] = {
      val fake = Fake(boardSettings = s"BBS_ID_RESET=$reset\n")
      fake.post()().map(_ => fake.md5Inputs.filter(_.startsWith("Mozilla")).toSeq)
    }
    for {
      negativeZero <- seed("-0")
      underflow <- seed("-1e-2147483649")
      positive <- seed("86400")
    } yield {
      assertEquals(negativeZero, Seq("Mozilla/5.0 (Test)sample-Infinitym(salt)"))
      assertEquals(negativeZero, underflow)
      assertNotEquals(negativeZero, positive)
    }
  }

  test("連投規制の材料は識別子の後に作り、IP と識別子をハッシュにして addRes に渡す") {
    val fake = Fake(boardSettings = "BBS_TITLE=看板\nBBS_SAMBA24=30\ntimecount=5\n")
    fake.post(reply()*)().map { outcome =>
      val check = fake.postings.head._3.rateLimit.getOrElse(fail("rateLimit が無い"))
      assertEquals(check.level, PostRateLevel.Enforce(Some(1)))
      assertEquals(check.identity, PostRateIdentity(s"s2(192.0.2.1:$salt)", s"s2(${outcome.context.shikibetsu}:$salt)", ""))
      assertEquals((check.board.timecount, check.board.timeclose, check.board.samba24, check.board.fr), (5, 10, 30, 1))
      assertEquals((check.thread.count, check.thread.resetSeconds), (40, 3600d))
      assert(outcome.isInstanceOf[PostingOutcome.Posted], outcome.toString)
    }
  }

  test("認証済の投稿者は aid を照合値にする") {
    val fake = Fake(user = Some(PostingUser("account", "alice")))
    fake.post()().map(_ => assertEquals(fake.postings.head._3.rateLimit.map(_.identity.aid), Some(s"s2(account:$salt)")))
  }

  test("板単位の件数と秒数は板設定だけ、スレ単位はスレ設定を優先して読む") {
    val fake = Fake(boardSettings = "BBS_SAMBA24=20\nBBS_FR_SECOND=4\nBBS_THREAD_RENTOU=3\n", threadSettings = "BBS_SAMBA24=0<>BBS_FR_SECOND=0<>timecount=1<>BBS_THREAD_RENTOU=7<>")
    fake.post(reply()*)().map { _ =>
      val check = fake.postings.head._3.rateLimit.get
      assertEquals((check.board.samba24, check.board.fr, check.board.timecount, check.thread.count), (20, 4, 18, 7))
    }
  }

  test("不正な設定は拒否せず既定値で判定する") {
    val fake = Fake(boardSettings = "BBS_SAMBA24=abc\ntimecount=-3\nBBS_FR_LEVEL=yes\nBBS_RENTOU_KEYS=id\n")
    fake.post()().map { outcome =>
      assert(outcome.isInstanceOf[PostingOutcome.Posted], outcome.toString)
      val check = fake.postings.head._3.rateLimit.get
      assertEquals((check.level, check.board.samba24, check.board.timecount), (PostRateLevel.Enforce(Some(1)), 10, 18))
    }
  }

  test("BBS_FR_LEVEL が 0 か空なら規制したまま、板には広域連投規制を外して渡す") {
    val cases = Seq("BBS_FR_LEVEL=0\n" -> (PostRateLevel.Enforce(None), 0), "BBS_FR_LEVEL=\n" -> (PostRateLevel.Enforce(None), 0),
      "" -> (PostRateLevel.Enforce(Some(1)), 4), "BBS_FR_LEVEL=30\n" -> (PostRateLevel.Enforce(Some(30)), 4))
    Future.traverse(cases) { case (text, _) =>
      val fake = Fake(boardSettings = text + "BBS_FR_SECOND=4\n")
      fake.post(reply()*)().map { _ =>
        val check = fake.postings.head._3.rateLimit.get
        (check.level, check.boardSettings.fr)
      }
    }.map(results => assertEquals(results, cases.map(_._2)))
  }

  test("キャップを判定するときは識別子（★）を照合値にしない") {
    val fake = Fake(boardSettings = "BBS_RENTOU_PASS=admin\n", caps = Map("pass" -> "管理人"))
    fake.post("mail" -> "#!pass")().map { outcome =>
      assertEquals(outcome.context.shikibetsu, "★")
      assertEquals(fake.postings.head._3.rateLimit.map(_.identity), Some(PostRateIdentity(s"s2(192.0.2.1:$salt)", "", "")))
    }
  }

  test("off・BBS_LIVE・キャップは連投規制を判定しない") {
    val cases = Seq(
      Fake(boardSettings = "BBS_FR_LEVEL=off\n") -> Seq.empty[(String, String)],
      Fake(boardSettings = "BBS_LIVE=checked\n") -> Seq.empty[(String, String)],
      Fake(boardSettings = "BBS_LIVE=1\n") -> Seq.empty[(String, String)],
      Fake(caps = Map("pass" -> "管理人")) -> Seq("mail" -> "#!pass"),
      Fake(boardSettings = "BBS_RENTOU_PASS=login\n", user = Some(PostingUser("account", "alice"))) -> Seq.empty[(String, String)])
    Future.traverse(cases) { case (fake, fields) => fake.post(fields*)().map(_ => fake.postings.head._3.rateLimit) }
      .map(results => assertEquals(results, Seq.fill(cases.size)(None)))
  }

  test("スレ立て時のスレ設定に連投規制の新しいキーを書き写さない") {
    val fake = Fake(boardSettings = "BBS_TITLE=看板\nBBS_SAMBA24=5\nBBS_FR_SECOND=2\nBBS_FR_LEVEL=log\ntimecount=3\n")
    fake.post()().map(_ => assertEquals(fake.postings.head._3.initialSettings, Some("BBS_TITLE=看板<>timecount=3<>BBS_SHIKIBETSU=off<>")))
  }

  test("連投規制の拒否は PostRate にし、文言は待ち秒数を出し、過去ログ目次と headline を書かない") {
    val fake = Fake(write = _ => Future.successful(ThreadWriteResult.RateLimited(PostRateRejection.Samba24(7))))
    fake.post(reply()*)().map { outcome =>
      val r = rejected(outcome)
      assertEquals(r.error, PostingError.PostRate(PostRateRejection.Samba24(7)))
      assertEquals(r.error.code, "post-rate:board:samba24:7")
      assertEquals(PostingText.message(r.error), "Samba24 で規制中です。(あと 7 秒) しばらく待ってから投稿してください。")
      assertEquals(PostingText.message(PostingError.PostRate(PostRateRejection.Gontakure)), "連続投稿ですか？？ しばらく待ってから投稿してください。")
      assertEquals(PostingError.PostRate(PostRateRejection.Fr(1)).code, "post-rate:board:fr:1")
      assertEquals(PostingText.message(PostingError.PostRate(PostRateRejection.Fr(1))), "広域連投規制中です。(あと 1 秒) しばらく待ってから投稿してください。")
      assertEquals(PostingText.message(PostingError.PostRate(PostRateRejection.ThreadRentou)), "このスレッドへの連続投稿が多すぎます。しばらく待ってから投稿してください。")
      assertEquals(r.headers, Seq("Set-Cookie" -> pon))
      assert(!fake.calls.exists(_.startsWith("archive")))
    }
  }

  test("試行モードの判定は拒否せずに書き、ハッシュ済みの値だけを記録する") {
    val findings = Seq(PostRateFinding.Rejected(PostRateRejection.Fr(1)), PostRateFinding.Copipe)
    val fake = Fake(write = _ => Future.successful(ThreadWriteResult.Written(2, findings)))
    fake.post(reply()*)().map { outcome =>
      assert(outcome.isInstanceOf[PostingOutcome.Posted], outcome.toString)
      assertEquals(fake.observations.toList, List(PostRateObservation("sample", "1699990000", 2, findings, s"s2(192.0.2.1:$salt)", s"s2(${outcome.context.shikibetsu}:$salt)")))
      assert(fake.calls.contains("archive.writeHeadline"))
    }
  }

  test("吸い込みは成功として採番予定の番号を返し、headline を書かない") {
    val fake = Fake(write = _ => Future.successful(ThreadWriteResult.Swallowed(2)))
    fake.post(reply()*)().map { outcome =>
      outcome match {
        case PostingOutcome.Swallowed(context, headers) =>
          assertEquals(context.number, 2)
          assertEquals(headers, Seq("Set-Cookie" -> pon))
        case other => fail(s"吸い込まなかった: $other")
      }
      assert(!fake.calls.exists(_.startsWith("archive")))
      assertEquals(fake.observations.toList, Nil)
    }
  }

  test("試行モードの記録先が壊れても確定した投稿を失敗にせず、後続の目次を更新する") {
    val findings = Seq(PostRateFinding.Rejected(PostRateRejection.Samba24(3)))
    val fake = Fake(
      boardSettings = "BBS_FR_LEVEL=log\n",
      write = _ => Future.successful(ThreadWriteResult.Written(1, findings)),
      record = _ => throw new IllegalStateException("log unavailable")
    )
    fake.post()().map { outcome =>
      assert(outcome.isInstanceOf[PostingOutcome.Posted], outcome.toString)
      assertEquals(outcome.context.number, 1)
      assertEquals(fake.postings.size, 1)
      assertEquals(fake.observations.size, 1)
      assertEquals(fake.writes.map(_._1).toList, List("BandList(sample)", "ThreadIndex(sample,1700000000)", "headline"))
    }
  }
}
