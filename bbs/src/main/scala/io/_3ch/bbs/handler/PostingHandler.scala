package io._3ch.bbs.handler

import io._3ch.bbs.external.cloudflare.*
import io._3ch.bbs.external.web.{Request, Response}
import io._3ch.bbs.hash.{Md5, WebHash}
import io._3ch.bbs.repository.BoardRepository
import io._3ch.bbs.domain.posting.{BrokenLogKind, ClientIdentity, FailureSite, FormSanitizer, GlobalThread, KeyIp, NusiMark, PostField, PostRouting, PostingContext, PostingError, PostingUser, ThreadOwner}
import io._3ch.bbs.domain.posting.moderation.ThreadCreationRejection
import io._3ch.bbs.durable.codec.ThreadOwnerCodec
import io._3ch.common.domain.{BbsSettings, BoardId, ThreadKey}
import io._3ch.common.domain.posting.{PostForm, PostKind}

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.scalajs.js.JSStringOps.*
import scala.scalajs.js.typedarray.Uint8Array

/**
  * bbs.cgi書き込み処理ハンドラ
  */
object PostingHandler {
  private final case class Rejected(error: PostingError) extends RuntimeException
  private def str(v: js.Any): String = js.Dynamic.global.String(v).asInstanceOf[String]
  private def truth(v: js.Any): Boolean = js.Dynamic.global.Boolean(v).asInstanceOf[Boolean]
  private def number(v: js.Any): Double = js.Dynamic.global.Number(v).asInstanceOf[Double]
  private def int(v: js.Any): Double = js.Dynamic.global.parseInt(v).asInstanceOf[Double]
  private def eq(v: js.Any, s: String): Boolean = v == s.asInstanceOf[js.Any]
  private def or(v: js.Any, default: String): String = if truth(v) then str(v) else default
  private def trim(s: String): String = s.asInstanceOf[js.Dynamic].trim().asInstanceOf[String]
  private def replace(s: String, pattern: String, replacement: String, flags: String = "g"): String =
    s.asInstanceOf[js.Dynamic].replace(new js.RegExp(pattern, flags), replacement).asInstanceOf[String]
  private def matches(s: String, pattern: String): Boolean = new js.RegExp(pattern).test(s)
  private def reject(error: PostingError): Nothing = throw Rejected(error)
  private def sha(s: String): Future[String] = WebHash.sha1Base64(s).toFuture
  private def hash(s: String): Future[String] = WebHash.hash("SHA-256", s).toFuture
  private def md5(s: String): String = Md5.md5(s)
  private def digest(g: js.Dynamic, value: String): Future[String] = if value.isEmpty then Future.successful("") else hash(value + ":" + str(g.md5Salt))
  private def thread(d: Draft, e: WorkerEnv): ThreadDoStub = e.THREAD_DO.get(e.THREAD_DO.idFromName(s"${d.form.bbs}/${d.form.key}"))
  private def errorText(e: Throwable): String = e match {
    case x: js.JavaScriptException => str(x.exception.asInstanceOf[js.Any])
    case _ => e.toString
  }
  private def site(g: js.Dynamic, d: Draft, clock: String, time: js.Any): FailureSite = FailureSite(clock, str(time), d.form.bbs, d.form.key, str(g.number))
  private def guarded[A](g: js.Dynamic, d: Draft, stage: String, description: String, clock: String)(f: => Future[A]): Future[A] =
    Future.unit.flatMap(_ => f).recover { case rejected: Rejected => throw rejected; case e =>
      val time = if clock == "time" || stage == "getThreadDataAndThreadSettings" || stage == "updateThreadFile" then g.time else g.nowTime
      reject(PostingError.Internal(stage, description, site(g, d, clock, time), errorText(e)))
    }

  /** 投稿処理中のフォーム。エラー画面にはその時点の値を出す。 */
  private final class Draft(var form: PostForm)

  def entry(request: Request, env: WorkerEnv, resolveUser: PostingUserResolver.Resolver = PostingUserResolver.unresolved): js.Promise[Response] = {
    val rawEnv = env.asInstanceOf[js.Dynamic]
    val req = PostingRequest.from(request)
    val d = new Draft(PostForm())
    val ip: js.Any = req.connectingIp.orNull
    val g = js.Dynamic.literal(
      version = "2025/7/31", time = str(js.Date.now()), unixtime = str(math.floor(js.Date.now() / 1000)),
      cookies = js.Dynamic.literal(HAP = "", D = "", U = "", PON = "", time = "", PIN = "", cert = ""),
      host = ip, ip = ip, userAgent = req.userAgent, browserInfo = "", deviceInfo = req.deviceInformation,
      asn = req.asn.getOrElse("0000"), country = req.country.getOrElse("unknown"), ken = req.region.getOrElse("catv?"), city = req.city.getOrElse(""),
      uid = "", userInfo = "", shikibetsuId = "",
      md5Salt = md5(str(rawEnv.salt)), boardDisableNusi = false, threadOwner = null, cap = 0, strongCap = 0, sage = 0,
      path = "PATH", logPath = "LOGPATH", logFile = "LOGDAT", logData = "", subject = "", number = 0,
      settings = js.Dynamic.literal(), boardSettings = js.Dynamic.literal(), headers = js.Dictionary.empty[String]
    )
    (for {
      _ <- Future { checkRequest(g, req) }
      _ <- readForm(g, d, request, req.isMonazilla)
      _ <- processCap(g, d, env)
      _ = checkGlobalThread(g, d)
      _ = { g.path = d.form.bbs + "/"; g.logPath = str(g.path) + "thread/" + d.form.key.take(4) + "/"; g.logFile = str(g.logPath) + d.form.key + ".cgi" }
      board <- boardSettings(g, d, env)
      _ <- threadSettings(g, d, env)
      _ <- identify(g, d, req)
      user <- resolveUser(request, env)
      _ = g.userInfo = s"UID:${str(g.uid)} IP:${str(g.host)} AS${str(g.asn)}-${str(g.country)},${str(g.ken)},${str(g.city)}Device:${str(g.deviceInfo)} UA:${str(g.userAgent)}"
      moderated <- Moderation.decide(g, d.form, env)
      _ = d.form = moderated._2
      _ = moderated._1.foreach(denial => reject(PostingError.Moderated(denial)))
      _ = checkSizes(g, d)
      creationLimit <- threadCreationLimit(g, d, req, board)
      _ <- makeShikibetsu(g, d, user)
      owner <- markNusi(g, d, user)
      _ = prepare(g, d)
      _ <- updateThread(g, d, env, creationLimit, owner)
      _ <- updateSubback(g, d, env)
      _ <- updateHeadline(g, d, env)
    } yield respond(g, d, None)).recover { case Rejected(error) => respond(g, d, Some(error)) }.toJSPromise
  }

  private def respond(g: js.Dynamic, d: Draft, error: Option[PostingError]): Response = {
    val charset = if d.form.isSJIS then "Shift_JIS" else "UTF-8"
    val headers = g.headers.asInstanceOf[js.Dictionary[String]]
    if error.isEmpty then { headers("x-threadkey") = d.form.key; headers("x-resnum") = str(g.number) }
    val html = error.fold(PostingPages.respondEndHtml(d.form, charset))(e => PostingPages.respondError(d.form, str(g.host), PostingText.message(e), charset))
    RendererShell.legacyReact(html, encoding = charset, extra = headers)
  }

  private def checkRequest(g: js.Dynamic, req: PostingRequest): Unit = {
    req.validate.foreach(reject)
    g.userAgent = req.browserUserAgent
    g.browserInfo = req.browserInfo
    g.host = req.host
  }

  private def readForm(g: js.Dynamic, d: Draft, r: Request, isMonazilla: Boolean): Future[Unit] = r.arrayBuffer().toFuture.map { buffer =>
    FormSanitizer.decode(new Uint8Array(buffer), isMonazilla).flatMap(form => PostRouting.route(form, str(g.unixtime))) match {
      case Left((error, form)) => d.form = form; reject(error)
      case Right(form) => d.form = form
    }
  }

  private def checkGlobalThread(g: js.Dynamic, d: Draft): Unit = GlobalThread.kindOf(d.form.key).foreach { kind =>
    val cap = truth(g.cap)
    if d.form.kind == PostKind.NewThread && !cap then reject(PostingError.GlobalThreadCreateCapOnly)
    if !GlobalThread.canPost(kind, cap) then reject(PostingError.GlobalThreadCapOnly)
  }

  private def processCap(g: js.Dynamic, d: Draft, e: WorkerEnv): Future[Unit] = {
    val pass = d.form.mail.split("#", -1).lift(1).filter(_.nonEmpty)
    pass match {
      case None => Future.unit
      case Some(password) =>
        val capPass = if password.startsWith("!") then Future.successful(password.drop(1)) else hash(md5(password) + str(g.md5Salt)).flatMap(sha)
        capPass.flatMap(p => e.CAP.get(p).toFuture).map { result =>
          if truth(result.asInstanceOf[js.Any]) then {
            var handle = str(result.asInstanceOf[js.Any])
            g.cap = 1
            if matches(handle, "★$") then { g.strongCap = 1; handle = replace(handle, "★.*", "", "") }
            d.form = d.form.copy(name = (if d.form.name.isEmpty then "" else d.form.name + "＠") + handle + " ★")
          }
        }
    }
  }

  private def boardSettings(g: js.Dynamic, d: Draft, e: WorkerEnv): Future[BbsSettings] = guarded(g, d, "getBoardSettings", "不明なエラーが発生しました。", "time") {
    val board = BoardId.create(d.form.bbs).fold(_ => reject(PostingError.ForbiddenBoard), identity)
    BoardRepository.getBoardSettings(board, e).map { boardSettings =>
      g.settings = boardSettings.value.toJSDictionary.asInstanceOf[js.Dynamic]
      g.boardSettings = g.settings
      g.boardDisableNusi = eq(g.settings.BBS_DISABLE_NUSI, "checked")
      boardSettings
    }
  }
  private def threadSettings(g: js.Dynamic, d: Draft, e: WorkerEnv): Future[Unit] = {
    val stub = thread(d, e)
    guarded(g, d, "getThreadDataAndThreadSettings", "不明なエラーが発生しました。", "unixtime") {
      if eq(g.settings.BBS_HEISA, "checked") then reject(PostingError.Closed)
      if d.form.kind == PostKind.NewThread then stub.getDat().toFuture.map { dat =>
        if dat.nonEmpty then reject(PostingError.DuplicateKey)
        g.logData = d.form.subject + "\n"; g.number = 1; g.subject = d.form.subject
      } else {
        val datF = stub.getDat(d.form.bbs, d.form.key).toFuture
        val settingsF = stub.getSettings().toFuture
        val ownerF = stub.getOwner().toFuture
        datF.zip(settingsF).zip(ownerF).map { case ((dat, settings), owner) =>
          if dat.isEmpty then reject(PostingError.ThreadMissing(str(g.logFile)))
          val lines = dat.split("\n", -1)
          if lines(0).isEmpty then reject(PostingError.BrokenLog(BrokenLogKind.Empty))
          g.subject = lines(0).split("<>", -1).last
          if !truth(g.subject) then reject(PostingError.BrokenLog(BrokenLogKind.NoTitle))
          g.number = lines.length
          BbsSettings.parse(settings, "<>").value.foreach((k, v) => g.settings.updateDynamic(k)(v))
          g.threadOwner = owner
          if lines.length >= 1000 then reject(PostingError.ThreadFull)
          if eq(g.settings.BBS_HEISA, "checked") then reject(PostingError.Closed)
        }
      }
    }
  }
  private def identify(g: js.Dynamic, d: Draft, req: PostingRequest): Future[Unit] = {
    g.settings.BBS_SHIKIBETSU = "off"
    req.cookie.split("[;,]\\s*", -1).foreach { pair =>
      val parts = pair.split("=", -1)
      if parts.length >= 2 && parts(0).nonEmpty && parts(1).nonEmpty then g.cookies.updateDynamic(parts(0))(parts(1))
    }
    hash(md5(str(g.ip)) + str(g.md5Salt)).flatMap(sha).map { pon =>
      g.headers.updateDynamic("Set-Cookie")(s"PON=$pon; Max-Age=63072000; path=/; ${if !d.form.isMonazilla then "Secure;" else ""} HttpOnly")
    }
  }

  private def checkSizes(g: js.Dynamic, d: Draft): Unit = {
    if truth(g.strongCap) then return
    def setting(name: String, fallback: Int): js.Any = {
      val v = g.settings.selectDynamic(name)
      if v == null || js.isUndefined(v) then fallback else v
    }
    for (value, settingName, default, kind) <- List((d.form.subject, "BBS_SUBJECT_COUNT", 64, PostField.Subject), (d.form.name, "BBS_NAME_COUNT", 64, PostField.Name), (d.form.mail, "BBS_MAIL_COUNT", 64, PostField.Mail)) do {
      val len = value.length
      val limit = setting(settingName, default)
      if len > number(limit) then reject(PostingError.FieldTooLong(kind, len, str(limit)))
    }
    val message = d.form.message
    if message.startsWith("!ADMIN:") then return
    var limit = setting("BBS_MESSAGE_COUNT", 4096)
    if number(g.number) == 1 && number(limit) < 65536 then limit = 65536
    if message.length > number(limit) then reject(PostingError.FieldTooLong(PostField.Message, message.length, str(limit)))
    val lines = message.split("<br>", -1).length
    val lineLimit = number(setting("BBS_LINE_NUMBER", 30)) * 2
    if number(g.number) >= 2 && lines > lineLimit then reject(PostingError.TooManyLines(lines, str(lineLimit)))
    if d.form.name.isEmpty then d.form = d.form.copy(name = or(g.settings.BBS_NONAME_NAME, "名無しさん＠３ちゃんねる"))
  }

  private def makeShikibetsu(g: js.Dynamic, d: Draft, user: Option[PostingUser]): Future[Unit] = {
    val group = or(g.settings.BBS_ID_GROUP, d.form.bbs)
    val date = str(math.floor((int(g.unixtime) + 32400) / number(or(g.settings.BBS_ID_RESET, "86400"))))
    def id(seed: String, len: Int) = sha(md5(seed + group + date + str(g.md5Salt))).map(_.take(len))
    val shiki = if truth(g.cap) then Future.successful("★") else or(g.settings.BBS_ID_SHIKIBETSU, "checked") match {
      case "checked" =>
        for {
          ken <- id(str(g.ken), 2)
          city <- id(str(g.city) + "c", 2)
          asn <- id(str(g.asn), 2)
        } yield s"${str(g.country)}.$ken.$city $asn-${md5(str(g.userAgent) + group + date + str(g.md5Salt)).take(16)}"
      case "raw" => Future.successful(s"${str(g.country)}.${str(g.ken)} ${str(g.asn)}")
      case "ken" => Future.successful(s"${str(g.country)}.${str(g.ken)}")
      case "city" => Future.successful(s"${str(g.country)}.${str(g.ken)}.${str(g.city)}")
      case "as" => Future.successful(str(g.asn))
      case _ => Future.successful("")
    }
    shiki.map { s =>
      g.shikibetsuId = s
      d.form = d.form.copy(name = d.form.name + PostingUser.nameSuffix(user, d.form.showId, truth(g.cap)) + s" </b>($s)<b>")
    }
  }

  /** スレ立てなら保存する `owner` を返し、返信なら該当時に名前欄へ ` (主)` を付ける。キャップは対象外（docs/トリップ・ID廃止とスレ主表示.md §2.4）。 */
  private def markNusi(g: js.Dynamic, d: Draft, user: Option[PostingUser]): Future[js.UndefOr[js.Dynamic]] =
    if truth(g.cap) then Future.successful(js.undefined) else {
      val ip = if g.ip == null || js.isUndefined(g.ip) then "" else KeyIp.of(str(g.ip))
      for {
        ipHash <- digest(g, ip)
        accountHash <- digest(g, user.fold("")(_.accountKey))
      } yield {
        val poster = ThreadOwner(ipHash, str(g.shikibetsuId), accountHash)
        if number(g.number) == 1 then ThreadOwnerCodec.encode(poster)
        else {
          val owner = if truth(g.threadOwner) then Some(ThreadOwnerCodec.decode(g.threadOwner)) else None
          if NusiMark.applies(isReply = true, cap = false, noNusi = d.form.noNusi, boardDisabled = truth(g.boardDisableNusi),
            threadDisabled = eq(g.settings.BBS_DISABLE_NUSI, "checked"), owner = owner, poster = poster) then d.form = d.form.copy(name = d.form.name + NusiMark.suffix)
          js.undefined
        }
      }
    }

  private def prepare(g: js.Dynamic, d: Draft): Unit =
    if number(g.number) >= 2 then {
      if d.form.mail.contains("sage") then g.sage = 1
      if int(d.form.key) + int(or(g.settings.BBS_FORCE_SAGE, "9999999999")) < int(g.unixtime) then g.sage = 1
      if eq(g.settings.BBS_LIVE, "checked") && js.Math.random() * 10 >= 1 then g.sage = 1
    }

  private def threadCreationLimit(g: js.Dynamic, d: Draft, req: PostingRequest, boardSettings: BbsSettings): Future[js.UndefOr[js.Dynamic]] = {
    if (number(g.number) != 1) Future.successful(js.undefined)
    else {
      val board = BoardId.create(d.form.bbs).fold(_ => reject(PostingError.ForbiddenBoard), identity)
      val key = d.form.key.toLongOption.toRight("").flatMap(ThreadKey.create).fold(_ => reject(PostingError.InvalidKey), identity)
      if (truth(g.cap)) return Future.successful(js.undefined)
      val settings = ThreadCreationSettings.fromSettings(boardSettings).fold(message => reject(PostingError.InvalidCreationSettings(message)), identity)
      val country = req.country.getOrElse("").trim.toUpperCase
      val asn = req.asn.getOrElse("").trim.toLongOption.filter(n => n > 0 && n <= 4294967295L).map(_.toString).getOrElse("")
      val region = req.region.getOrElse("").trim
      val network = if (country.matches("[A-Z]{2}") && country != "JP" && country != "XX" && country != "T1") "overseas"
        else if (country == "JP" && asn.nonEmpty && settings.asOnly(asn)) s"AS:$asn"
        else if (country == "JP" && asn.nonEmpty && region.nonEmpty) s"AS:$asn:JP:$region"
        else ""
      val rawIp = req.connectingIp.getOrElse("")
      val ip = if (rawIp.contains(":")) {
        try js.Dynamic.newInstance(js.Dynamic.global.URL)(s"http://[$rawIp]/").hostname.asInstanceOf[String]
        catch { case _: Throwable => rawIp.toLowerCase }
      } else rawIp
      val context = PostingContext(board, key, true, d.form.subject, ClientIdentity(ip, req.userAgent, network))
      for {
        ipHash <- digest(g, context.identity.ip)
        uaHash <- digest(g, context.identity.userAgent)
        networkHash <- digest(g, context.identity.networkGroup)
        titleHash <- digest(g, context.title)
      } yield js.Dynamic.literal(ip = ipHash, userAgent = uaHash, networkGroup = networkHash, title = titleHash,
        tatesugi = settings.limits.threadTatesugi, tatesugiResetSeconds = settings.limits.threadTatesugiResetSeconds,
        junban = settings.limits.threadJunban, junbanResetSeconds = settings.limits.threadJunbanResetSeconds)
    }
  }

  private object CreationRejected {
    def unapply(error: Throwable): Option[ThreadCreationRejection] = ThreadCreationRejection.fromCode(errorText(error).stripPrefix("Error: "))
  }

  private def updateThread(g: js.Dynamic, d: Draft, e: WorkerEnv, creationLimit: js.UndefOr[js.Dynamic], owner: js.UndefOr[js.Dynamic]): Future[Unit] = {
    val stub = thread(d, e)
    guarded(g, d, "updateThreadFile", "ログファイルに書けませんでした。", "unixtime") {
      val initial: js.UndefOr[String] = if number(g.number) == 1 then js.Object.keys(g.settings.asInstanceOf[js.Object]).map(k => k + "=" + str(g.settings.selectDynamic(k)) + "<>").mkString else js.undefined
      val formatter = js.Dynamic.newInstance(js.Dynamic.global.Intl.DateTimeFormat)("ja-JP", js.Dynamic.literal(year = "numeric", month = "2-digit", day = "2-digit", weekday = "short", hour = "2-digit", minute = "2-digit", second = "2-digit", timeZone = "Asia/Tokyo"))
      val date = formatter.format(new js.Date(int(g.unixtime) * 1000)).asInstanceOf[String]
      d.form = d.form.copy(message = d.form.message.jsReplace("<br>", " <br> "))
      val dat = s"${d.form.name}<><>$date<> ${d.form.message} <>${if number(g.number) == 1 then str(g.subject) else ""}"
      stub.addRes(dat, s"${str(g.number)}<>${str(g.userInfo)}", js.Dynamic.literal(bbs = d.form.bbs, key = d.form.key, title = g.subject, sage = truth(g.sage), maxThreads = int(or(g.boardSettings.BBS_MAX_MENU_THREAD, "700")), create = number(g.number) == 1, initialSettings = initial, creationLimit = creationLimit, owner = owner)).toFuture.map(n => g.number = n).recover {
        case CreationRejected(rejection) => reject(PostingError.ThreadCreation(rejection))
      }
    }
  }

  private def updateSubback(g: js.Dynamic, d: Draft, e: WorkerEnv): Future[Unit] =
    if number(g.number) > 1 then Future.unit else guarded(g, d, "updateSubback", "不明なエラーが発生しました。", "unixtime") {
      val bucket = e.ch3www.asInstanceOf[js.Dynamic]
      def get(file: String): Future[js.Dynamic] = bucket.get(file).asInstanceOf[js.Promise[js.Dynamic]].toFuture
      def text(obj: js.Dynamic): Future[String] = if obj == null then Future.successful("") else obj.text().asInstanceOf[js.Promise[String]].toFuture.map(_.split("\n", -1).drop(1).mkString("\n"))
      def save(file: String, value: String, etag: js.Any, label: String): Future[Unit] = {
        val condition = if truth(etag) then js.Dynamic.literal(etagMatches = etag) else js.Dynamic.literal(doesNotExist = true)
        bucket.put(file, value, js.Dynamic.literal(onlyIf = condition)).asInstanceOf[js.Promise[js.Dynamic]].toFuture.map { result =>
          if result == null || !truth(result.uploaded) then reject(PostingError.ArchiveConflict(label, site(g, d, "unixtime", g.nowTime)))
        }
      }
      val subFile = str(g.logPath) + "subject.txt"
      val archive = str(g.path) + "thread/subject.txt"
      for {
        obj <- get(subFile)
        old <- text(obj)
        sub = s"${str(g.unixtime)}\n${d.form.key}<>${d.form.subject}<>${d.form.name}<>${str(g.shikibetsuId)}<><>${d.form.message}\n$old"
        _ <- if obj == null then
          for {
            arch <- get(archive)
            list <- text(arch)
            _ <- save(archive, s"${str(g.unixtime)}\n${d.form.key.take(4)}<>${d.form.key}\n$list", if arch == null then js.undefined else arch.etag, "kakologList")
            _ <- save(subFile, sub, js.undefined, "SubbackFIle")
          } yield ()
        else save(subFile, sub, obj.etag, "SubbackFIle")
      } yield ()
    }

  private def updateHeadline(g: js.Dynamic, d: Draft, e: WorkerEnv): Future[Unit] =
    if truth(g.sage) then Future.unit else guarded(g, d, "updateHeadline", "不明なエラーが発生しました。", "unixtime") {
      val bucket = e.ch3www.asInstanceOf[js.Dynamic]
      for {
        obj <- bucket.get("headline.txt").asInstanceOf[js.Promise[js.Dynamic]].toFuture
        old <- if obj == null then Future.successful("") else obj.text().asInstanceOf[js.Promise[String]].toFuture
        text = new StringBuilder(s"${str(g.unixtime)}\n${d.form.bbs}<>${str(g.boardSettings.BBS_TITLE)}<>${str(g.unixtime)}<>${d.form.key}<>${d.form.message.take(30)}\n")
        _ = if obj != null then old.split("\n", -1).slice(1, 301).foreach { line =>
          val f = trim(line).split("<>", -1)
          if f.length >= 5 && matches(f(0), "^[a-zA-Z0-9\\-_]+$") && f(1).nonEmpty && d.form.bbs != f(0) then text.append(f.take(5).mkString("<>")).append('\n')
        }
        _ <- bucket.put("headline.txt", text.toString, js.Dynamic.literal(onlyIf = js.Dynamic.literal(etagMatches = if obj == null then js.undefined else obj.etag))).asInstanceOf[js.Promise[js.Any]].toFuture
      } yield ()
    }
}
