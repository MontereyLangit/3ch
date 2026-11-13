package io._3ch.bbs.handler

import munit.FunSuite
import io._3ch.bbs.durable.DurableTestKit
import io._3ch.bbs.durable.DurableTestKit.Env
import io._3ch.bbs.domain.posting.PostingUser
import io._3ch.bbs.external.web.{Request, TextEncoder}
import io._3ch.bbs.util.SjisEncoding
import scala.collection.mutable
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scala.scalajs.js.typedarray.{ArrayBuffer, Uint8Array}

@js.native
@JSImport("node:fs", JSImport.Namespace)
private object SnapshotFs extends js.Object {
  def readFileSync(path: String, encoding: String): String = js.native
  def writeFileSync(path: String, data: String, encoding: String): Unit = js.native
  def existsSync(path: String): Boolean = js.native
  def mkdirSync(path: String, options: js.Dynamic): Unit = js.native
  def readdirSync(path: String): js.Array[String] = js.native
  def unlinkSync(path: String): Unit = js.native
}

@js.native
@JSImport("node:path", JSImport.Namespace)
private object SnapshotPath extends js.Object {
  def join(paths: String*): String = js.native
  def resolve(paths: String*): String = js.native
}

/**
 * bbs.cgi 投稿処理の入出力スナップショット（tests/snapshots/posting の JSON）。固定時刻・固定乱数で応答と保存内容を照合する。
 * 再生成は `sbt "bbs/testOnly io._3ch.bbs.handler.PostingSnapshotSuite -- --include-tags=update"`
 */
class PostingSnapshotSuite extends FunSuite {
  import HandlerTestKit.*

  private val Now = 1700000000d
  private val NowKey = "1700000000"
  private val ExistingKey = "1699990000"
  private val OtherKey = "1699980000"
  private val Update = new munit.Tag("update")

  private final case class Case(
    name: String,
    fields: Seq[(String, String)] = Nil,
    headers: Seq[(String, String)] = Nil,
    removeHeaders: Set[String] = Set.empty,
    cf: Option[Seq[(String, String)]] = None,
    sjis: Boolean = false,
    submit: Option[String] = Some("書き込む"),
    method: String = "POST",
    random: Double = 0.5,
    user: Option[PostingUser] = None,
    setup: Env => Future[Unit] = _ => Future.unit,
    boards: Seq[String] = Seq("sample")
  )

  private val DefaultHeaders: Seq[(String, String)] = Seq(
    "content-type" -> "application/x-www-form-urlencoded",
    "referer" -> "https://www.3chbbs.com/test/read.cgi/sample/1/",
    "user-agent" -> "Mozilla/5.0 (TestBrowser)",
    "CF-Connecting-IP" -> "192.0.2.1"
  )
  private val MonazillaUa = Seq("user-agent" -> "Monazilla/1.00 (TestNavi/1.0)")
  private val DefaultCf: Seq[(String, String)] = Seq("country" -> "JP", "asn" -> "2516", "region" -> "Tokyo", "city" -> "Chiyoda")
  private val CreateFields: Seq[(String, String)] = Seq(
    "FROM" -> "", "mail" -> "", "MESSAGE" -> "テスト本文", "subject" -> "テストスレ", "bbs" -> "sample", "key" -> "", "time" -> "1699999000"
  )
  private val ReplyFields: Seq[(String, String)] = Seq("subject" -> "", "key" -> ExistingKey, "MESSAGE" -> "返信本文")

  private def merge(base: Seq[(String, String)], over: Seq[(String, String)]): Seq[(String, String)] = {
    val overrides = over.toMap
    val keys = base.map(_._1.toLowerCase).toSet
    base.map((k, v) => k -> overrides.getOrElse(k, v)) ++ over.filterNot((k, _) => keys.contains(k.toLowerCase))
  }

  private def sjisPercentEncode(text: String): String = sjisBytes(text).map(b => "%" + f"$b%02X").mkString

  private def encode(value: String, sjis: Boolean): String = if sjis then sjisPercentEncode(value) else js.URIUtils.encodeURIComponent(value)

  private def makeRequest(c: Case): Request = {
    val headers = merge(DefaultHeaders, c.headers).filterNot((k, _) => c.removeHeaders.contains(k.toLowerCase))
    val lowered = headers.map((k, v) => k.toLowerCase -> v).toMap
    val form = merge(CreateFields, c.fields).map((k, v) => s"$k=${encode(v, c.sjis)}").mkString("&")
    val body = form + c.submit.map(s => s"&submit=${encode(s, c.sjis)}").getOrElse("")
    js.Dynamic.literal(
      method = c.method,
      cf = js.Dictionary(c.cf.getOrElse(DefaultCf)*),
      headers = js.Dynamic.literal(get = ((name: String) => lowered.getOrElse(name.toLowerCase, null)): js.Function1[String, String]),
      arrayBuffer = (() => js.Promise.resolve[js.Any](new TextEncoder().encode(body).buffer)): js.Function0[js.Promise[js.Any]]
    ).asInstanceOf[Request]
  }

  // ---- 準備 ----

  private val ThreadSettings = "BBS_TITLE=sample＠3ch掲示板<>BBS_NONAME_NAME=名無しさん＠３ちゃんねる<>BBS_SHIKIBETSU=off<>"
  private val ExistingDat = "名無しさん＠３ちゃんねる </b>(JP.ab.cd ef-0123456789abcdef)<b><><>2023/11/14(火) 19:26:40<> 最初の本文 <>既存スレ\n"
  private val ExistingLog = "1<>UID: IP:192.0.2.100 AS2516-JP,Tokyo,ChiyodaDevice: UA:Mozilla/5.0\n"

  private def seedThread(env: Env, name: String, entries: (String, js.Any)*): Future[Unit] =
    DurableTestKit.seed(env.threadInstance(name), entries*)

  private def existing(settings: String = ThreadSettings, dat: String = ExistingDat, bbs: String = "sample", extra: Seq[(String, js.Any)] = Nil, key: String = ExistingKey): Env => Future[Unit] = env =>
    for {
      _ <- seedThread(env, s"$bbs/$key", (Seq[(String, js.Any)]("dat" -> dat, "log" -> ExistingLog, "settings" -> settings) ++ extra)*)
      _ <- env.board(bbs).updateThread(js.Dynamic.literal(key = key, title = "既存スレ", resNum = 1)).toFuture
      _ <- env.board(bbs).updateThread(js.Dynamic.literal(key = OtherKey, title = "別スレ", resNum = 5)).toFuture
    } yield ()

  private def boardSetting(text: String, bbs: String = "sample"): Env => Future[Unit] = env => Future.successful(env.putR2(s"$bbs/SETTING.TXT", text))

  private def r2(key: String, text: String): Env => Future[Unit] = env => Future.successful(env.putR2(key, text))

  private def caps: Env => Future[Unit] = env => Future.successful {
    env.putCap("weakpass", "キャップ")
    env.putCap("strongpass", "つよいキャップ★")
    env.putCap("+3470EAMAflrDvV5NQgr8kedQTI", "通常キャップ")
  }

  private def all(steps: (Env => Future[Unit])*): Env => Future[Unit] = env =>
    steps.foldLeft(Future.unit)((acc, step) => acc.flatMap(_ => step(env)))

  /** 準備として at 秒に投稿処理を通す（スレ立て規制の履歴作り） */
  private def priorPost(at: Double, c: Case): Env => Future[Unit] = env =>
    withFixedTime(at * 1000)(fut[js.Dynamic](Posting.entry(makeRequest(c), env.worker, resolver(c)))).map(_ => ())

  /** 以後の THREAD_DO.get(...) が返すスタブの method を失敗させる */
  private def failThread(method: String, message: String): Env => Future[Unit] = env => Future.successful {
    val worker = env.worker.asInstanceOf[js.Dynamic]
    val original = worker.THREAD_DO
    worker.THREAD_DO = js.Dynamic.literal(
      idFromName = ((name: String) => original.idFromName(name)): js.Function1[String, js.Any],
      get = ((id: js.Any) => {
        val proxy = js.Object.create(original.get(id).asInstanceOf[js.Object]).asInstanceOf[js.Dynamic]
        proxy.updateDynamic(method)((() => js.Promise.reject(new js.Error(message))): js.Function0[js.Promise[js.Any]])
        proxy
      }): js.Function1[js.Any, js.Any]
    )
  }

  private def failR2Get(key: String): Env => Future[Unit] = env => Future.successful {
    env.onR2Get(k => if k == key then throw js.JavaScriptException(new js.Error(s"R2 get failed: $k")))
  }

  private def conflictOnR2Get(key: String): Env => Future[Unit] = env => Future.successful {
    var done = false
    env.onR2Get(k => if k == key && !done then { done = true; env.replaceR2Etag(k, "etag-conflict") })
  }

  private def resolver(c: Case): PostingUserResolver.Resolver = (_, _) => Future.successful(c.user)

  private val Alice = Some(PostingUser("aid1", "alice"))
  private val OtherClient = Seq("CF-Connecting-IP" -> "192.0.2.77", "user-agent" -> "Mozilla/5.0 (OtherBrowser)")
  /** IP だけが違い、識別子は既定の投稿者と同じになる */
  private val SameShikiClient = Seq("CF-Connecting-IP" -> "192.0.2.78")

  /** 準備として at 秒に ExistingKey へ返信する（連投規制の履歴作り） */
  private def priorReply(at: Double, headers: Seq[(String, String)] = Nil, fields: Seq[(String, String)] = Nil): Env => Future[Unit] =
    priorPost(at, Case("prior", fields = merge(ReplyFields, fields), headers = headers))

  /** 準備としてスレ ExistingKey を投稿処理で立てる（スレ主情報を残す） */
  private def ownThread(headers: Seq[(String, String)] = Nil, user: Option[PostingUser] = None): Env => Future[Unit] =
    priorPost(Now - 10000, Case("prior", headers = headers, user = user))

  /** 板 924 に共通スレ key を置く */
  private def globalThread(key: String): Env => Future[Unit] =
    existing(bbs = "924", key = key)

  // ---- ケース ----

  private def create(name: String, c: Case => Case = identity): Case = c(Case(name))
  private def reply(name: String, c: Case => Case = identity): Case = {
    val base = Case(name, fields = ReplyFields, setup = existing())
    c(base)
  }
  private def withFields(c: Case, fields: (String, String)*): Case = c.copy(fields = merge(c.fields, fields))

  private val cases: Seq[Case] = Seq(
    // スレ立て × 投稿者 × sage × 文字コード
    create("create-web"),
    create("create-web-sage", withFields(_, "mail" -> "sage")),
    create("create-web-named", withFields(_, "FROM" -> "名前", "mail" -> "メール")),
    create("create-sjis", _.copy(sjis = true, headers = MonazillaUa)),
    create("create-sjis-sage", c => withFields(c.copy(sjis = true, headers = MonazillaUa), "mail" -> "sage")),
    create("create-cap-weak", c => withFields(c.copy(setup = caps), "mail" -> "#!weakpass")),
    create("create-cap-weak-named", c => withFields(c.copy(setup = caps), "FROM" -> "名前", "mail" -> "＃!weakpass")),
    create("create-cap-hashed", c => withFields(c.copy(setup = caps), "mail" -> "#mynormalpass")),
    create("create-cap-wrong", c => withFields(c.copy(setup = caps), "mail" -> "#!wrongpass")),
    create("create-cap-strong", c => withFields(c.copy(setup = caps), "FROM" -> ("a" * 100), "mail" -> "#!strongpass", "subject" -> ("s" * 100))),
    create("create-cap-strong-sjis", c => withFields(c.copy(setup = caps, sjis = true, headers = MonazillaUa), "mail" -> "#!strongpass")),
    create("create-trip-discarded", withFields(_, "FROM" -> "名前#tripkey")),
    create("create-trip-entity", withFields(_, "FROM" -> "&#65;#key")),
    create("create-trip-cap", c => withFields(c.copy(setup = caps), "FROM" -> "名前#tripkey", "mail" -> "#!weakpass")),
    create("create-escape", withFields(_, "FROM" -> "a&b\"c<d>e'f\tg", "mail" -> "m<b>", "MESSAGE" -> "msg&<>\"'\t\u0001x&#10;y&#x0D;z&amp;", "subject" -> "<subject>")),
    create("create-fake-cap", withFields(_, "FROM" -> "偽★◆＃", "mail" -> "x＃y")),
    create("create-multiline", withFields(_, "MESSAGE" -> "1行目\n2行目\r\n3行目\r4行目")),
    create("create-br-in-fields", withFields(_, "FROM" -> "a\nb", "subject" -> "t\ns", "mail" -> "m\nn")),
    create("create-admin-message", withFields(_, "MESSAGE" -> ("!ADMIN:" + "x" * 70000))),
    create("create-long-message-ok", withFields(_, "MESSAGE" -> ("x" * 65536))),
    create("create-noname-board", _.copy(setup = boardSetting("BBS_NONAME_NAME=板の名無し\nBBS_TITLE=<DIR_NAME>の看板\n"))),
    create("create-board-title-empty", _.copy(setup = boardSetting("BBS_TITLE=\n"))),
    create("create-board-settings-order", _.copy(setup = boardSetting("Z_LAST=1\nA_FIRST=2\nBBS_TITLE=t=u\n BBS_SPACED = v \nBBS_NONAME_NAME=a=b\n"))),
    create("create-max-menu-thread", c => c.copy(setup = all(existing(), boardSetting("BBS_MAX_MENU_THREAD=2\n")))),
    create("create-headline-existing", _.copy(setup = r2("headline.txt", "1699999999\nother<>他板<>1699999999<>1699999999<>他板の本文\nsample<>古い<>1699999998<>1699999998<>同じ板\nbad line\n<>x<>y<>z<>w\n"))),
    create("create-subback-existing", _.copy(setup = all(r2("sample/thread/1700/subject.txt", "1699999999\n1700000099<>前のスレ<>名前<>識別<>ID:x<>本文\n"), r2("sample/thread/subject.txt", "1699999999\n1699<>1699999999\n")))),
    create("create-archive-existing", _.copy(setup = r2("sample/thread/subject.txt", "1699999999\n1699<>1699999999\n"))),
    create("create-other-board", withFields(_, "bbs" -> "other")).copy(boards = Seq("other")),
    create("create-newthread-command", withFields(_, "key" -> "9123", "subject" -> "", "MESSAGE" -> "!newthreadコマンドの題名\n本文")),
    create("create-newthread-command-literal-br", withFields(_, "key" -> "9123", "subject" -> "", "MESSAGE" -> "!newthread題名<br>本文")),
    create("create-newthread-command-no-nine", withFields(_, "key" -> "8123", "subject" -> "", "MESSAGE" -> "!newthread題名")),
    create("create-924", withFields(_, "key" -> "924", "subject" -> "x", "bbs" -> "sample")).copy(boards = Seq("sample", "924")),
    create("create-board-name-filtered", withFields(_, "bbs" -> "sam!ple")).copy(boards = Seq("sample")),
    create("create-time-nondigits", withFields(_, "time" -> "12a34")),
    create("create-key-with-subject", withFields(_, "key" -> "12345", "subject" -> "題名")),
    // 識別子・ID
    create("create-shikibetsu-raw", _.copy(setup = boardSetting("BBS_ID_SHIKIBETSU=raw\n"))),
    create("create-shikibetsu-ken", _.copy(setup = boardSetting("BBS_ID_SHIKIBETSU=ken\n"))),
    create("create-shikibetsu-city", _.copy(setup = boardSetting("BBS_ID_SHIKIBETSU=city\n"))),
    create("create-shikibetsu-as", _.copy(setup = boardSetting("BBS_ID_SHIKIBETSU=as\n"))),
    create("create-shikibetsu-other", _.copy(setup = boardSetting("BBS_ID_SHIKIBETSU=d\n"))),
    create("create-shikibetsu-empty", _.copy(setup = boardSetting("BBS_ID_SHIKIBETSU=\n"))),
    create("create-id-group-reset", _.copy(setup = boardSetting("BBS_ID_GROUP=grp\nBBS_ID_RESET=3600\n"))),
    // !id・スレ主表示
    create("create-name-id-user", c => withFields(c.copy(user = Alice), "FROM" -> "名前!id")),
    create("create-name-id-escape", c => withFields(c.copy(user = Some(PostingUser("aid2", "<b>\"x\"&'"))), "FROM" -> "!id")),
    create("create-name-id-anonymous", withFields(_, "FROM" -> "名前 !id")),
    create("create-name-id-cap", c => withFields(c.copy(user = Alice, setup = caps), "FROM" -> "名前!id", "mail" -> "#!weakpass")),
    create("create-showid-field", c => withFields(c.copy(user = Alice), "showid" -> "1")),
    create("create-name-id-word", c => withFields(c.copy(user = Alice), "FROM" -> "!idx !no")),
    reply("reply-name-id-user", c => withFields(c.copy(user = Alice), "FROM" -> "!id")),
    reply("reply-nusi-ip", _.copy(setup = ownThread())),
    reply("reply-nusi-account", c => c.copy(user = Alice, headers = OtherClient, setup = ownThread(user = Alice))),
    reply("reply-nusi-account-other", c => c.copy(user = Some(PostingUser("aid9", "bob")), headers = OtherClient, setup = ownThread(user = Alice))),
    reply("reply-nusi-other-ip", c => c.copy(headers = Seq("CF-Connecting-IP" -> "192.0.2.77"), setup = ownThread())),
    reply("reply-nusi-other-client", c => c.copy(headers = OtherClient, setup = ownThread())),
    reply("reply-nusi-ipv6-prefix", c => c.copy(headers = Seq("CF-Connecting-IP" -> "2001:db8:1:2::99"), setup = ownThread(headers = Seq("CF-Connecting-IP" -> "2001:db8:1:2::1")))),
    reply("reply-nusi-no", c => withFields(c.copy(setup = ownThread()), "FROM" -> "名前!no")),
    reply("reply-nusi-cap", c => withFields(c.copy(setup = all(ownThread(), caps)), "mail" -> "#!weakpass")),
    reply("reply-nusi-board-disabled", c => c.copy(setup = all(boardSetting("BBS_DISABLE_NUSI=checked\n"), ownThread()))),
    reply("reply-nusi-thread-disabled", c => c.copy(setup = all(ownThread(), env => env.threadInstance(s"sample/$ExistingKey").setSettings("BBS_DISABLE_NUSI=checked<>").toFuture))),
    create("create-nusi-cap-owner", c => withFields(c.copy(setup = caps), "mail" -> "#!weakpass")),
    // 全板共通スレ
    create("create-global-924-cap", c => withFields(c.copy(setup = caps), "mail" -> "#!weakpass", "MESSAGE" -> "!924 共通スレ本文")).copy(boards = Seq("sample", "924")),
    create("create-global-900-cap", c => withFields(c.copy(setup = caps), "mail" -> "#!weakpass", "MESSAGE" -> "!900<br>本文")).copy(boards = Seq("sample", "924")),
    create("create-global-9240-not-command", c => withFields(c.copy(setup = caps), "mail" -> "#!weakpass", "MESSAGE" -> "!9240 本文")).copy(boards = Seq("sample", "924")),
    create("error-global-create-not-cap", withFields(_, "MESSAGE" -> "!924 本文")).copy(boards = Seq("sample", "924")),
    create("error-global-empty-message", c => withFields(c.copy(setup = caps), "mail" -> "#!weakpass", "MESSAGE" -> "!924")).copy(boards = Seq("sample", "924")),
    reply("error-global-924-reply-not-cap", c => withFields(c.copy(setup = globalThread("9240000000")), "key" -> "9240000000", "bbs" -> "other")).copy(boards = Seq("924")),
    reply("reply-global-924-cap", c => withFields(c.copy(setup = all(globalThread("9240000000"), caps)), "key" -> "9240000000", "mail" -> "#!weakpass")).copy(boards = Seq("924")),
    reply("reply-global-900", c => withFields(c.copy(setup = globalThread("9000000000")), "key" -> "9000000000")).copy(boards = Seq("924")),
    create("create-ipv6", _.copy(headers = Seq("CF-Connecting-IP" -> "2001:DB8:0:0:0:0:0:1"))),
    create("create-ipv6-short", _.copy(headers = Seq("CF-Connecting-IP" -> "2001:db8::1"))),
    create("create-forwarded", _.copy(headers = Seq("x-forwarded-for" -> "203.0.113.5", "via" -> "1.1 proxy"))),
    create("create-forwarded-same-ip", _.copy(headers = Seq("x-forwarded-for" -> "192.0.2.1", "forwarded" -> "for=203.0.113.6"))),
    create("create-client-hints", _.copy(headers = Seq("sec-ch-ua" -> "\"Chromium\";v=\"120\"", "accept" -> "text/html", "x-ch3-device-information" -> "dev-info"))),
    create("create-client-hints-full", _.copy(headers = Seq("sec-ch-ua" -> "\"Chromium\";v=\"120\"", "sec-ch-ua-full-version-list" -> "\"Chromium\";v=\"120.0.1\""))),
    create("create-cf-missing", _.copy(cf = Some(Nil))),
    create("create-cf-overseas", _.copy(cf = Some(Seq("country" -> "US", "asn" -> "15169", "region" -> "California", "city" -> "Mountain View")))),
    create("create-no-ip", _.copy(removeHeaders = Set("cf-connecting-ip"))),
    create("create-cookie", _.copy(headers = Seq("cookie" -> "PON=old; HAP=x, D=y; empty="))),
    create("create-referer-local", _.copy(headers = Seq("referer" -> "http://127.0.0.1:8787/test/"))),
    create("create-referer-io", _.copy(headers = Seq("referer" -> "https://www.3ch.io"))),
    // 規制
    create("create-moderation-mark", _.copy(setup = r2("deny.cgi", "# comment\n<>IP:192\\.0\\.2\\.1 <><>mark\n"))),
    create("create-moderation-ngword-mark", _.copy(setup = r2("ngwords.cgi", "<><>テスト<>mark\n"))),
    create("create-moderation-no-madakana", _.copy(setup = all(boardSetting("BBS_NO_MADAKANA=checked\n"), r2("deny.cgi", "<>IP:192\\.0\\.2\\.1 <><>deny\n")))),
    create("create-moderation-ngword-pass", _.copy(setup = all(boardSetting("BBS_NGWORD_PASS=checked\n"), r2("ngwords.cgi", "<><>テスト<>deny\n")))),
    create("create-moderation-other-board", _.copy(setup = r2("deny.cgi", "^other$<><><>deny\n"))),
    create("create-moderation-saku", withFields(_, "bbs" -> "saku")).copy(setup = r2("deny.cgi", "<><><>deny\n"), boards = Seq("saku")),
    create("create-moderation-cap", c => withFields(c.copy(setup = all(caps, r2("deny.cgi", "<><><>deny\n"))), "mail" -> "#!weakpass")),
    create("create-moderation-decoded", withFields(_, "MESSAGE" -> "&#x41;&#66;&lt;")).copy(setup = r2("ngwords.cgi", "<><>AB<<>deny\n")),
    create("create-live", _.copy(setup = boardSetting("BBS_LIVE=checked\n"))),
    create("create-creation-as-only", _.copy(setup = boardSetting("BBS_THREAD_JUNBAN_AS_ONLY=AS2516\n"))),
    create("create-creation-limit-zero", c => c.copy(setup = all(boardSetting("BBS_THREAD_TATESUGI=0\nBBS_THREAD_JUNBAN=0\nBBS_FR_LEVEL=off\n"), priorPost(Now - 1, Case("prior"))))),
    create("create-creation-after-reset", c => c.copy(setup = all(boardSetting("BBS_THREAD_TATESUGI_RESET=1\nBBS_THREAD_JUNBAN=1/1\nBBS_FR_LEVEL=off\n"), priorPost(Now - 2, Case("prior"))))),
    create("create-creation-invalid-settings-cap", c => withFields(c.copy(setup = all(caps, boardSetting("BBS_THREAD_TATESUGI=invalid\n"))), "mail" -> "#!weakpass")),

    // 返信
    reply("reply-web"),
    reply("reply-web-sage", withFields(_, "mail" -> "sage")),
    reply("reply-web-sage-upper", withFields(_, "mail" -> "SAGE")),
    reply("reply-web-subject-ignored", withFields(_, "subject" -> "無視される題名")),
    reply("reply-sjis", _.copy(sjis = true, headers = MonazillaUa)),
    reply("reply-sjis-sage", c => withFields(c.copy(sjis = true, headers = MonazillaUa), "mail" -> "sage")),
    reply("reply-cap-weak", c => withFields(c.copy(setup = all(existing(), caps)), "mail" -> "#!weakpass")),
    reply("reply-cap-weak-sage", c => withFields(c.copy(setup = all(existing(), caps)), "mail" -> "sage#!weakpass")),
    reply("reply-cap-strong", c => withFields(c.copy(setup = all(existing(), caps)), "mail" -> "#!strongpass", "MESSAGE" -> ("y<br>" * 100))),
    reply("reply-trip-discarded", withFields(_, "FROM" -> "名前#tripkey")),
    reply("reply-noname-thread", _.copy(setup = existing(settings = "BBS_NONAME_NAME=スレの名無し<>"))),
    reply("reply-thread-settings-override", c => c.copy(setup = all(existing(settings = "BBS_NONAME_NAME=a=b<> BBS_ID = off <>BBS_ID_SHIKIBETSU=raw<>"), boardSetting("BBS_NONAME_NAME=板側\nBBS_TITLE=板タイトル\n")))),
    reply("reply-force-sage", _.copy(setup = existing(settings = "BBS_FORCE_SAGE=1<>"))),
    reply("reply-force-sage-not-yet", _.copy(setup = existing(settings = "BBS_FORCE_SAGE=86400<>"))),
    reply("reply-live-sage", c => c.copy(setup = all(existing(), boardSetting("BBS_LIVE=checked\n")), random = 0.1)),
    reply("reply-live-age", c => c.copy(setup = all(existing(), boardSetting("BBS_LIVE=checked\n")), random = 0.05)),
    reply("reply-live-thread", c => c.copy(setup = existing(settings = "BBS_LIVE=checked<>"), random = 0.5)),
    reply("reply-lines-ok", withFields(_, "MESSAGE" -> Seq.fill(60)("l").mkString("\n"))),
    reply("reply-lines-custom", c => withFields(c.copy(setup = all(existing(), boardSetting("BBS_LINE_NUMBER=2\n"))), "MESSAGE" -> "a\nb\nc\nd")),
    reply("reply-998", _.copy(setup = existing(dat = ExistingDat + "x<><>d<> m <>\n" * 997))),
    reply("reply-heisa-board-spaced", _.copy(setup = all(existing(), boardSetting("BBS_HEISA= checked\n")))),
    reply("reply-heisa-board-with-separator", _.copy(setup = all(existing(), boardSetting("BBS_HEISA=checked<>x\n")))),
    reply("reply-heisa-thread", _.copy(setup = existing(settings = "BBS_HEISA=checked<>"))),
    reply("reply-heisa-thread-spaced", _.copy(setup = existing(settings = "BBS_HEISA= checked <>"))),
    reply("reply-heisa-thread-empty", _.copy(setup = existing(settings = "BBS_HEISA=<>"))),
    reply("reply-heisa-thread-first", _.copy(setup = existing(settings = "BBS_HEISA=checked"))),
    reply("reply-heisa-thread-overridden", _.copy(setup = existing(settings = "BBS_HEISA=checked<>BBS_HEISA=<>"))),
    reply("reply-heisa-thread-leading-space", _.copy(setup = existing(settings = "BBS_HEISA= checked<>"))),
    reply("reply-heisa-thread-key-spaced", _.copy(setup = existing(settings = " BBS_HEISA=checked<>"))),
    reply("reply-heisa-thread-key-trailing-space", _.copy(setup = existing(settings = "BBS_HEISA =checked<>"))),
    reply("reply-heisa-thread-later-checked", _.copy(setup = existing(settings = "BBS_HEISA=<>BBS_HEISA=checked<>"))),
    reply("reply-heisa-thread-angle-overridden", _.copy(setup = existing(settings = "X=<<>BBS_HEISA=checked<>>Y=<>BBS_HEISA=<>"))),
    create("create-heisa-thread-settings-only", _.copy(setup = env => seedThread(env, s"sample/$NowKey", "settings" -> "BBS_HEISA=checked<>"))),
    create("error-internal-update-thread-duplicate-legacy", _.copy(setup = r2(s"sample/thread/1700/$NowKey.cgi", "過去スレ\n\n1<>名前<>識別<>1700000000<>ID:old<>過去本文<>HOST\n"))),
    reply("error-internal-update-thread-full-no-newline", _.copy(setup = existing(dat = ExistingDat + "x<><>d<> m <>\n" * 997 + "x<><>d<> m <>"))),
    reply("reply-legacy-import", c => c.copy(fields = merge(c.fields, Seq("key" -> "1600000000")), setup = r2("sample/thread/1600/1600000000.cgi",
      "過去スレ\nBBS_NONAME_NAME=過去の名無し<>\n1<>名前<>識別<>1600000000<>ID:old<>過去本文<>HOST\n3<><><>1600000100<><>三番目<>HOST\n"))),
    reply("reply-moderation-tatedame", _.copy(setup = all(existing(), r2("deny.cgi", "<>IP:192\\.0\\.2\\.1 <><>TateDame\n")))),
    reply("reply-moderation-mark-sjis", c => c.copy(sjis = true, headers = MonazillaUa, setup = all(existing(), r2("deny.cgi", "<>UA:Monazilla<><>mark\n")))),

    // エラー: リクエスト
    create("error-method", _.copy(method = "GET")),
    create("error-content-type", _.copy(headers = Seq("content-type" -> "text/plain"))),
    create("error-content-type-missing", _.copy(removeHeaders = Set("content-type"))),
    create("error-referer", _.copy(headers = Seq("referer" -> "https://www.3ch.io.evil.example/"))),
    create("error-referer-missing", _.copy(removeHeaders = Set("referer"))),
    create("error-referer-port", _.copy(headers = Seq("referer" -> "http://127.0.0.1:8789/"))),
    create("error-ua-short", _.copy(headers = Seq("user-agent" -> "short"))),
    create("error-ua-long", _.copy(headers = Seq("user-agent" -> ("M" * 385)))),
    create("error-ua-nonascii", _.copy(headers = Seq("user-agent" -> "Mozilla/5.0 日本語"))),
    create("error-client-ip", _.copy(headers = Seq("client-ip" -> "1.2.3.4"))),
    create("error-multiple", _.copy(method = "GET", headers = Seq("content-type" -> "text/plain", "client-ip" -> "9.9.9.9"))),
    create("error-no-submit", _.copy(submit = None)),
    create("error-empty-submit", _.copy(submit = Some(""))),
    create("error-sjis-from-web", _.copy(sjis = true)),
    create("error-sjis-from-web-other-submit", _.copy(submit = Some("送信"))),
    create("create-trip-key-long", withFields(_, "FROM" -> ("a#" + "k" * 1024))),
    create("error-forbidden-board", withFields(_, "bbs" -> "test")).copy(boards = Seq("test")),
    create("error-empty-board", withFields(_, "bbs" -> "!!")).copy(boards = Nil),
    create("error-board-id-invalid", withFields(_, "bbs" -> "foo_bar")).copy(boards = Seq("foo_bar")),
    create("error-board-id-invalid-cap", c => withFields(c.copy(setup = caps), "bbs" -> "foo_bar", "mail" -> "#!weakpass")).copy(boards = Seq("foo_bar")),
    create("error-empty-message", withFields(_, "MESSAGE" -> "")),
    create("error-missing-subject", withFields(_, "subject" -> "")),
    create("error-missing-subject-key", withFields(_, "subject" -> "", "key" -> "abc")),
    create("error-time-empty", withFields(_, "time" -> "")),
    create("error-time-letters", withFields(_, "time" -> "abc")),
    create("error-subject-too-long", withFields(_, "subject" -> ("s" * 65))),
    create("error-name-too-long", withFields(_, "FROM" -> ("n" * 65))),
    create("error-mail-too-long", withFields(_, "mail" -> ("m" * 65))),
    create("error-message-too-long-create", withFields(_, "MESSAGE" -> ("x" * 65537))),
    create("error-board-limits", c => withFields(c.copy(setup = boardSetting("BBS_SUBJECT_COUNT=3\n")), "subject" -> "abcd")),
    create("error-board-limits-nan", c => withFields(c.copy(setup = boardSetting("BBS_NAME_COUNT=abc\n")), "FROM" -> "n")),
    reply("error-message-too-long-reply", withFields(_, "MESSAGE" -> ("x" * 4097))),
    reply("error-message-too-long-board", c => withFields(c.copy(setup = all(existing(), boardSetting("BBS_MESSAGE_COUNT=10\n"))), "MESSAGE" -> ("x" * 11))),
    reply("error-too-many-lines", withFields(_, "MESSAGE" -> Seq.fill(61)("l").mkString("\n"))),
    // エラー: 板・スレ
    create("error-heisa-board", _.copy(setup = boardSetting("BBS_HEISA=checked\n"))),
    create("error-heisa-board-spaced", _.copy(setup = boardSetting("BBS_HEISA= checked\n"))),
    create("create-heisa-board-with-separator", _.copy(setup = boardSetting("BBS_HEISA=checked<>x\n"))),
    create("create-heisa-board-uppercase", _.copy(setup = boardSetting("BBS_HEISA=CHECKED\n"))),
    reply("error-heisa-board-reply", _.copy(setup = all(existing(), boardSetting("BBS_HEISA=checked\n")))),
    reply("error-thread-missing", withFields(_, "key" -> "1699000000")),
    reply("error-thread-hidden", _.copy(setup = existing(extra = Seq("hidden" -> true)))),
    reply("error-log-empty-line", _.copy(setup = existing(dat = "\nabc\n"))),
    reply("error-log-no-title", _.copy(setup = existing(dat = "a<>b<>c<>d<>\n"))),
    reply("error-thread-full", _.copy(setup = existing(dat = ExistingDat + "x<><>d<> m <>\n" * 998))),
    create("error-concurrent-creation", _.copy(setup = env => seedThread(env, s"sample/$NowKey", "dat" -> "x<><>d<> m <>t\n"))),
    // エラー: 規制
    create("error-moderation-deny-host", _.copy(setup = r2("deny.cgi", "<>IP:192\\.0\\.2\\.1 <><>deny\n"))),
    create("error-moderation-deny-md5", _.copy(setup = r2("deny.cgi", "sample<>UA:Mozilla<><>md5\n"))),
    create("error-moderation-ngword", _.copy(setup = r2("ngwords.cgi", "<><>テスト本文<>\n"))),
    create("error-moderation-ngword-deny-cgi", _.copy(setup = all(r2("deny.cgi", "<><>テスト<>deny\n"), r2("ngwords.cgi", "<><>テスト<>mark\n")))),
    create("error-moderation-tatedame", _.copy(setup = r2("deny.cgi", "<>IP:192\\.0\\.2\\.1 <><>TateDame\n"))),
    create("error-moderation-tor", _.copy(cf = Some(Seq("country" -> "T1", "asn" -> "1", "region" -> "", "city" -> "")))),
    create("error-moderation-broken-rules", _.copy(setup = r2("deny.cgi", "broken line\n"))),
    create("error-moderation-broken-action", _.copy(setup = r2("ngwords.cgi", "<><>x<>block\n"))),
    create("error-moderation-broken-regex", _.copy(setup = r2("deny.cgi", "<>(<><>deny\n"))),
    create("error-creation-narrow", c => c.copy(setup = priorPost(Now - 1, Case("prior", fields = Seq("subject" -> "前のスレ"))))),
    create("error-creation-same-title", c => c.copy(setup = priorPost(Now - 1, Case("prior", headers = Seq("CF-Connecting-IP" -> "192.0.2.9", "user-agent" -> "Mozilla/5.0 Other"), cf = Some(Seq("country" -> "JP", "asn" -> "2516", "region" -> "Osaka", "city" -> "Osaka")))))),
    create("error-creation-broad", c => c.copy(
      cf = Some(Seq("country" -> "GB", "asn" -> "123", "region" -> "", "city" -> "")),
      headers = Seq("CF-Connecting-IP" -> "192.0.2.50"),
      fields = merge(c.fields, Seq("subject" -> "海外2")),
      setup = priorPost(Now - 1, Case("prior", fields = Seq("subject" -> "海外1"), headers = Seq("CF-Connecting-IP" -> "198.51.100.1", "user-agent" -> "Mozilla/5.0 Other"), cf = Some(Seq("country" -> "US", "asn" -> "15169", "region" -> "", "city" -> ""))))
    )),
    create("error-creation-invalid-settings", _.copy(setup = boardSetting("BBS_THREAD_TATESUGI=invalid\n"))),
    create("error-creation-invalid-format", _.copy(setup = boardSetting("BBS_THREAD_JUNBAN=1/2/3\n"))),
    create("error-creation-invalid-as-only", _.copy(setup = boardSetting("BBS_THREAD_JUNBAN_AS_ONLY=AS0\n"))),
    create("error-creation-value-with-equals", _.copy(setup = boardSetting("BBS_THREAD_TATESUGI=0=invalid\n"))),
    create("error-creation-spaced-keys", c => c.copy(setup = all(boardSetting(" BBS_THREAD_TATESUGI = 0\n BBS_THREAD_JUNBAN =0\nBBS_FR_LEVEL=off\n"), priorPost(Now - 1, Case("prior"))))),
    // 連投規制。キーが無ければ規制し、log は拒否せず記録だけする。BBS_FR_LEVEL が 0 か空なら広域連投規制だけを外す
    reply("error-post-rate-samba24", _.copy(setup = all(existing(), boardSetting("BBS_FR_LEVEL=\n"), priorReply(Now - 3)))),
    reply("error-post-rate-gontakure", _.copy(setup = all(existing(), boardSetting("BBS_SAMBA24=0\ntimeclose=1\n"), priorReply(Now - 100)))),
    reply("error-post-rate-thread", _.copy(setup = all(existing(), boardSetting("BBS_SAMBA24=0\nBBS_THREAD_RENTOU=1\n"), priorReply(Now - 100)))),
    reply("reply-post-rate-copipe-swallowed", _.copy(setup = all(existing(), boardSetting("BBS_FR_LEVEL=\n"), priorReply(Now - 100, OtherClient)))),
    reply("reply-post-rate-copipe-log", _.copy(setup = all(existing(), boardSetting("BBS_FR_LEVEL=log\n"), priorReply(Now - 100, OtherClient)))),
    reply("reply-post-rate-samba24-log", _.copy(setup = all(existing(), boardSetting("BBS_FR_LEVEL=log\n"), priorReply(Now - 3, fields = Seq("MESSAGE" -> "前の本文"))))),
    reply("error-post-rate-fr", _.copy(setup = all(existing(), boardSetting("BBS_FR_LEVEL=1\n"), priorReply(Now - 1, SameShikiClient, Seq("MESSAGE" -> "前の本文"))))),
    reply("reply-post-rate-fr-level-zero", _.copy(setup = all(existing(), boardSetting("BBS_FR_LEVEL=0\n"), priorReply(Now - 1, SameShikiClient, Seq("MESSAGE" -> "前の本文"))))),
    reply("reply-post-rate-same-shiki", _.copy(setup = all(existing(), boardSetting("BBS_FR_LEVEL=\n"), priorReply(Now - 3, SameShikiClient, Seq("MESSAGE" -> "前の本文"))))),
    reply("reply-post-rate-live", _.copy(setup = all(existing(), boardSetting("BBS_LIVE=checked\n"), priorReply(Now - 3, fields = Seq("MESSAGE" -> "前の本文"))))),
    reply("reply-post-rate-cap", c => withFields(c.copy(setup = all(existing(), caps, boardSetting("BBS_FR_LEVEL=\n"),
      priorReply(Now - 3, fields = Seq("MESSAGE" -> "前の本文", "mail" -> "#!weakpass")))), "mail" -> "#!weakpass")),
    // エラー: 内部エラー
    create("error-internal-board-settings", _.copy(setup = all(boardSetting("BBS_TITLE=x\n"), failR2Get("sample/SETTING.TXT")))),
    create("error-internal-thread-read-create", _.copy(setup = failThread("getDat", "getDat failed"))),
    reply("error-internal-thread-read-reply", c => c.copy(setup = all(existing(), failThread("getSettings", "getSettings failed")))),
    reply("error-internal-update-thread-stopped", _.copy(setup = existing(extra = Seq("stopped" -> true)))),
    create("error-internal-update-thread-fail", _.copy(setup = failThread("addRes", "addRes failed"))),
    create("error-internal-subback-conflict", _.copy(setup = all(r2("sample/thread/1700/subject.txt", "1699999999\n"), conflictOnR2Get("sample/thread/1700/subject.txt")))),
    create("error-internal-archive-conflict", _.copy(setup = all(r2("sample/thread/subject.txt", "1699999999\n"), conflictOnR2Get("sample/thread/subject.txt")))),
    create("error-internal-subback-read", _.copy(setup = all(r2("sample/thread/1700/subject.txt", "1699999999\n"), failR2Get("sample/thread/1700/subject.txt")))),
    create("error-internal-headline", _.copy(setup = all(r2("headline.txt", "1\n"), failR2Get("headline.txt")))),
    reply("error-internal-headline-reply", c => c.copy(setup = all(existing(), r2("headline.txt", "1\n"), failR2Get("headline.txt")))),
    create("error-moderation-r2-failure", _.copy(setup = all(r2("deny.cgi", "<><>x<>deny\n"), failR2Get("deny.cgi"))))
  )

  // ---- 実行と記録 ----

  private def base64(buffer: ArrayBuffer): String =
    js.Dynamic.global.Buffer.from(buffer).applyDynamic("toString")("base64").asInstanceOf[String]

  private def nullable(value: Option[String]): js.Any = value.fold(null.asInstanceOf[js.Any])(v => v)

  private def responseJson(res: js.Dynamic): Future[js.Any] =
    fut[ArrayBuffer](res.arrayBuffer()).map { buffer =>
      val headers = js.Array[js.Any]()
      res.headers.forEach(((value: String, name: String) => { headers.push(js.Array(name, value)); () }): js.Function2[String, String, Unit])
      val contentType = Option(res.headers.get("content-type").asInstanceOf[String]).getOrElse("")
      val bytes = new Uint8Array(buffer)
      val text = if contentType.contains("Shift_JIS") then SjisEncoding.decode(bytes)
        else js.Dynamic.newInstance(js.Dynamic.global.TextDecoder)("utf-8").decode(bytes).asInstanceOf[String]
      js.Dynamic.literal(status = res.status, headers = headers, bodyBase64 = base64(buffer), bodyText = text)
    }

  private def threadJson(env: Env, name: String): Future[(String, js.Any)] = {
    val stub = env.threadInstance(name)
    for {
      dat <- stub.getDat().toFuture
      log <- stub.getLog().toFuture
      settings <- stub.getSettings().toFuture
      owner <- stub.getOwner().toFuture
    } yield name -> js.Dynamic.literal(dat = dat, log = log, settings = settings, owner = owner)
  }

  private def run(c: Case): Future[String] =
    withFixedTime(Now * 1000) {
      withRandom(c.random) {
        val env = DurableTestKit.newEnv()
        for {
          _ <- c.setup(env)
          outcome <- fut[js.Dynamic](Posting.entry(makeRequest(c), env.worker, resolver(c))).transformWith {
            case scala.util.Success(res) => responseJson(res).map(json => "response" -> json)
            case scala.util.Failure(e) => Future.successful("failure" -> (DurableTestKit.messageOf(e): js.Any))
          }
          _ = env.onR2Get(_ => ())
          threadNames = c.boards.flatMap(b => Seq(s"$b/$NowKey", s"$b/$ExistingKey") ++ (if b == "924" then Seq("924/9240000000", "924/9000000000", s"924/924${NowKey.drop(3)}", s"924/900${NowKey.drop(3)}") else Nil)) ++ (if c.name == "reply-legacy-import" then Seq("sample/1600000000") else Nil)
          threads <- Future.traverse(threadNames)(threadJson(env, _))
          subjects <- Future.traverse(c.boards)(b => env.board(b).getSubject().toFuture.map(s => b -> (s: js.Any)))
        } yield {
          val r2Keys = c.boards.flatMap(b => Seq(s"$b/thread/1700/subject.txt", s"$b/thread/1699/subject.txt", s"$b/thread/subject.txt") ++ (if b == "924" then Seq("924/thread/9240/subject.txt", "924/thread/9000/subject.txt") else Nil)) :+ "headline.txt"
          val result = js.Dictionary[js.Any](
            "case" -> c.name,
            outcome,
            "threads" -> js.Dictionary(threads*),
            "boards" -> js.Dictionary(subjects*),
            "r2" -> js.Dictionary(r2Keys.map(k => k -> nullable(env.r2Text(k)))*)
          )
          js.JSON.stringify(result, null.asInstanceOf[js.Array[js.Any]], 2) + "\n"
        }
      }
    }

  private lazy val snapshotDir: String = {
    var dir = SnapshotPath.resolve(".")
    var depth = 0
    while depth < 8 && !SnapshotFs.existsSync(SnapshotPath.join(dir, "tests", "golden")) do {
      dir = SnapshotPath.resolve(SnapshotPath.join(dir, ".."))
      depth += 1
    }
    SnapshotPath.join(dir, "tests", "snapshots", "posting")
  }

  private def file(name: String): String = SnapshotPath.join(snapshotDir, name + ".json")

  private var verified = false

  assertEquals(cases.map(_.name).distinct.size, cases.size)

  cases.foreach { c =>
    test(c.name) {
      verified = true
      run(c).map { actual =>
        if !SnapshotFs.existsSync(file(c.name)) then fail(s"スナップショットがない: ${file(c.name)}（-- --include-tags=update で生成）")
        assertEquals(actual, SnapshotFs.readFileSync(file(c.name), "utf8"))
      }
    }
  }

  test("update".tag(Update)) {
    if verified then Future.unit
    else {
      SnapshotFs.mkdirSync(snapshotDir, js.Dynamic.literal(recursive = true))
      val names = cases.map(_.name).toSet
      SnapshotFs.readdirSync(snapshotDir).filter(f => f.endsWith(".json") && !names.contains(f.stripSuffix(".json"))).foreach(f => SnapshotFs.unlinkSync(SnapshotPath.join(snapshotDir, f)))
      cases.foldLeft(Future.unit) { (acc, c) =>
        acc.flatMap(_ => run(c)).map(json => SnapshotFs.writeFileSync(file(c.name), json, "utf8"))
      }
    }
  }
}
