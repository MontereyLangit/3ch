package io._3ch.bbs.handler

import io._3ch.bbs.external.cloudflare.*
import io._3ch.bbs.external.web.{Request, Response}
import io._3ch.bbs.hash.{Md5, WebHash}
import io._3ch.bbs.application.posting.ports.Clock
import io._3ch.bbs.repository.{ArchiveIndexRepository, BoardRepository, CapRepository, ModerationRuleRepository, ThreadRepository}
import io._3ch.bbs.domain.posting.{BrokenLogKind, ClientIdentity, FailureClock, FailureSite, GlobalThread, KeyIp, NusiMark, PostField, PostRouting, PostingContext, PostingError, PostingIdentity, PostingStage, PostingUser, ThreadOwner, ThreadPosting}
import io._3ch.bbs.domain.posting.moderation.{ModerationDecision, ModerationInput, ModerationService, ThreadCreationLimit, ThreadCreationRejection, ThreadCreationRequest}
import io._3ch.bbs.durable.codec.ThreadOwnerCodec
import io._3ch.common.domain.{BbsSettings, BoardId, ThreadKey}
import io._3ch.common.domain.posting.{CapHandle, DatLineFormatter, NameField, PostForm, PostKind, UserInfo}
import io._3ch.common.domain.identity.{DateSalt, ShikibetsuFormat, ShikibetsuMode, ShikibetsuSeeds}

import scala.collection.immutable.ListMap
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
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
  private def trim(s: String): String = s.asInstanceOf[js.Dynamic].trim().asInstanceOf[String]
  private def matches(s: String, pattern: String): Boolean = new js.RegExp(pattern).test(s)
  private def reject(error: PostingError): Nothing = throw Rejected(error)
  private def sha(s: String): Future[String] = WebHash.sha1Base64(s).toFuture
  private def hash(s: String): Future[String] = WebHash.hash("SHA-256", s).toFuture
  private def md5(s: String): String = Md5.md5(s)
  private def digest(d: Draft, value: String): Future[String] = if value.isEmpty then Future.successful("") else hash(value + ":" + d.identity.salt)
  private def thread(d: Draft, e: WorkerEnv): ThreadDoStub = e.THREAD_DO.get(e.THREAD_DO.idFromName(s"${d.form.bbs}/${d.form.key}"))
  private def errorText(e: Throwable): String = e match {
    case x: js.JavaScriptException => str(x.exception.asInstanceOf[js.Any])
    case _ => e.toString
  }
  private def site(g: js.Dynamic, d: Draft, clock: FailureClock, time: js.Any): FailureSite = FailureSite(clock, str(time), d.form.bbs, d.form.key, str(g.number))
  private def guarded[A](g: js.Dynamic, d: Draft, stage: PostingStage, clock: FailureClock)(f: => Future[A]): Future[A] =
    Future.unit.flatMap(_ => f).recover { case rejected: Rejected => throw rejected; case e =>
      val time = if clock == FailureClock.Time || stage == PostingStage.ThreadDataAndSettings || stage == PostingStage.ThreadWrite then g.time else g.nowTime
      reject(PostingError.Internal(stage, site(g, d, clock, time), errorText(e)))
    }

  /** 処理中のフォームと設定（板設定を返信ならスレ設定で上書き）。エラー画面にはその時点のフォームを出す */
  private final class Draft(var form: PostForm, var settings: BbsSettings, var identity: PostingIdentity) {
    var cap: Option[CapHandle] = None
    var shikibetsu: String = ""
    var threadOwner: () => Option[ThreadOwner] = () => None
    var ponCookie: Option[String] = None
  }

  private val clock = Clock.system
  private def logFile(d: Draft): String = s"${d.form.bbs}/thread/${d.form.key.take(4)}/${d.form.key}.cgi"

  def entry(request: Request, env: WorkerEnv, resolveUser: PostingUserResolver.Resolver = PostingUserResolver.unresolved): js.Promise[Response] = {
    val rawEnv = env.asInstanceOf[js.Dynamic]
    val req = PostingRequest.from(request)
    val d = new Draft(PostForm(), BbsSettings(ListMap.empty), PostingIdentity(
      req.connectingIp, req.connectingIp.getOrElse("null"), req.userAgent, "", req.deviceInformation,
      req.asn.getOrElse("0000"), req.country.getOrElse("unknown"), req.region.getOrElse("catv?"), req.city.getOrElse(""), md5(str(rawEnv.salt))
    ))
    val g = js.Dynamic.literal(
      version = "2025/7/31", time = str(clock.nowMillis()), unixtime = str(math.floor(clock.nowMillis() / 1000)),
      cookies = js.Dynamic.literal(HAP = "", D = "", U = "", PON = "", time = "", PIN = "", cert = ""),
      sage = 0,
      subject = "", number = 0
    )
    (for {
      _ <- Future { checkRequest(d, req) }
      _ <- readForm(g, d, request, req.isMonazilla)
      _ <- processCap(d, env)
      _ = GlobalThread.postingError(d.form.key, d.form.kind == PostKind.NewThread, d.cap.isDefined).foreach(reject)
      board <- boardSettings(g, d, env)
      _ <- threadSettings(g, d, board, env)
      _ <- identify(g, d, req)
      user <- resolveUser(request, env)
      userInfo = UserInfo("", d.identity.host, d.identity.asn, d.identity.country, d.identity.region, d.identity.city, d.identity.deviceInfo, d.identity.userAgent)
      _ <- moderate(g, d, userInfo, env)
      _ = checkSizes(g, d)
      creationLimit <- threadCreationLimit(g, d, req, board.settings)
      _ <- makeShikibetsu(g, d, user)
      owner <- markNusi(g, d, user, board.settings)
      _ = prepare(g, d)
      _ <- updateThread(g, d, board, env, creationLimit, owner, userInfo)
      _ <- updateSubback(g, d, env)
      _ <- updateHeadline(g, d, env)
    } yield respond(g, d, None)).recover { case Rejected(error) => respond(g, d, Some(error)) }.toJSPromise
  }

  private def respond(g: js.Dynamic, d: Draft, error: Option[PostingError]): Response = {
    val charset = if d.form.isSJIS then "Shift_JIS" else "UTF-8"
    val posted = if error.isEmpty then Seq("x-threadkey" -> d.form.key, "x-resnum" -> str(g.number)) else Nil
    val headers = d.ponCookie.map("Set-Cookie" -> _).toSeq ++ posted
    val html = error.fold(PostingPages.respondEndHtml(d.form, charset))(e => PostingPages.respondError(d.form, d.identity.host, PostingText.message(e), charset))
    RendererShell.legacyReact(html, encoding = charset, extra = headers)
  }

  private def checkRequest(d: Draft, req: PostingRequest): Unit = {
    req.validate.foreach(reject)
    d.identity = d.identity.copy(userAgent = req.browserUserAgent, browserInfo = req.browserInfo, host = req.host)
  }

  private def readForm(g: js.Dynamic, d: Draft, r: Request, isMonazilla: Boolean): Future[Unit] = r.arrayBuffer().toFuture.map { buffer =>
    PostingFormDecoder.decode(new Uint8Array(buffer), isMonazilla).flatMap(form => PostRouting.route(form, str(g.unixtime))) match {
      case Left((error, form)) => d.form = form; reject(error)
      case Right(form) => d.form = form
    }
  }

  private def processCap(d: Draft, e: WorkerEnv): Future[Unit] = CapHandle.password(d.form.mail) match {
    case None => Future.unit
    case Some(password) =>
      val capPass = if password.startsWith("!") then Future.successful(password.drop(1)) else hash(md5(password) + d.identity.salt).flatMap(sha)
      capPass.flatMap(CapRepository.get(_, e)).map { cap =>
        d.cap = cap
        cap.foreach(handle => d.form = d.form.copy(name = handle.displayName(d.form.name)))
      }
  }

  private final case class LoadedBoard(id: BoardId, settings: BbsSettings)

  private def boardSettings(g: js.Dynamic, d: Draft, e: WorkerEnv): Future[LoadedBoard] = guarded(g, d, PostingStage.BoardSettings, FailureClock.Time) {
    val board = BoardId.create(d.form.bbs).fold(_ => reject(PostingError.ForbiddenBoard), identity)
    BoardRepository.getBoardSettings(board, e).map { boardSettings =>
      d.settings = boardSettings
      LoadedBoard(board, boardSettings)
    }
  }
  private def threadSettings(g: js.Dynamic, d: Draft, board: LoadedBoard, e: WorkerEnv): Future[Unit] = {
    val stub = thread(d, e)
    guarded(g, d, PostingStage.ThreadDataAndSettings, FailureClock.Unixtime) {
      if d.settings.checked("BBS_HEISA") then reject(PostingError.Closed)
      if d.form.kind == PostKind.NewThread then stub.getDat().toFuture.map { dat =>
        if dat.nonEmpty then reject(PostingError.DuplicateKey)
        g.number = 1; g.subject = d.form.subject
      } else {
        val datF = stub.getDat(d.form.bbs, d.form.key).toFuture
        val settingsF = ThreadRepository.getSettings(board.id, d.form.key, e)
        val ownerF = stub.getOwner().toFuture
        datF.zip(settingsF).zip(ownerF).map { case ((dat, settings), owner) =>
          if dat.isEmpty then reject(PostingError.ThreadMissing(logFile(d)))
          val lines = dat.split("\n", -1)
          if lines(0).isEmpty then reject(PostingError.BrokenLog(BrokenLogKind.Empty))
          g.subject = lines(0).split("<>", -1).last
          if !truth(g.subject) then reject(PostingError.BrokenLog(BrokenLogKind.NoTitle))
          g.number = lines.length
          d.settings = d.settings.merged(settings)
          d.threadOwner = () => if truth(owner) then Some(ThreadOwnerCodec.decode(owner.asInstanceOf[js.Dynamic])) else None
          if lines.length >= 1000 then reject(PostingError.ThreadFull)
          if d.settings.checked("BBS_HEISA") then reject(PostingError.Closed)
        }
      }
    }
  }
  private def identify(g: js.Dynamic, d: Draft, req: PostingRequest): Future[Unit] = {
    d.settings = d.settings.updated("BBS_SHIKIBETSU", "off")
    req.cookie.split("[;,]\\s*", -1).foreach { pair =>
      val parts = pair.split("=", -1)
      if parts.length >= 2 && parts(0).nonEmpty && parts(1).nonEmpty then g.cookies.updateDynamic(parts(0))(parts(1))
    }
    hash(md5(d.identity.ip.getOrElse("null")) + d.identity.salt).flatMap(sha).map { pon =>
      d.ponCookie = Some(s"PON=$pon; Max-Age=63072000; path=/; ${if !d.form.isMonazilla then "Secure;" else ""} HttpOnly")
    }
  }

  private def moderate(g: js.Dynamic, d: Draft, userInfo: UserInfo, e: WorkerEnv): Future[Unit] = {
    val input = ModerationInput(d.form, userInfo, d.cap.isDefined, d.identity.country, number(g.number), d.settings)
    ModerationService.check(input, ModerationRuleRepository.load(_, e)).map {
      case ModerationDecision.Pass(form) => d.form = form
      case ModerationDecision.Deny(denial, form) => d.form = form; reject(PostingError.Moderated(denial))
    }
  }

  private def checkSizes(g: js.Dynamic, d: Draft): Unit = {
    if d.cap.exists(_.strong) then return
    /** 上限値と、文言に出す上限の文字列（設定値そのもの、無ければ既定値） */
    def limit(name: String, default: Int): (Double, String) = (d.settings.number(name, default), d.settings.get(name).getOrElse(default.toString))
    for (value, settingName, kind) <- List((d.form.subject, "BBS_SUBJECT_COUNT", PostField.Subject), (d.form.name, "BBS_NAME_COUNT", PostField.Name), (d.form.mail, "BBS_MAIL_COUNT", PostField.Mail)) do {
      val (max, shown) = limit(settingName, 64)
      if value.length > max then reject(PostingError.FieldTooLong(kind, value.length, shown))
    }
    val message = d.form.message
    if message.startsWith("!ADMIN:") then return
    val (messageMax, messageShown) = {
      val configured = limit("BBS_MESSAGE_COUNT", 4096)
      if number(g.number) == 1 && configured._1 < 65536 then (65536d, "65536") else configured
    }
    if message.length > messageMax then reject(PostingError.FieldTooLong(PostField.Message, message.length, messageShown))
    val lines = message.split("<br>", -1).length
    val lineLimit = d.settings.number("BBS_LINE_NUMBER", 30) * 2
    if number(g.number) >= 2 && lines > lineLimit then reject(PostingError.TooManyLines(lines, str(lineLimit)))
    if d.form.name.isEmpty then d.form = d.form.copy(name = d.settings.orElse("BBS_NONAME_NAME", "名無しさん＠３ちゃんねる"))
  }

  private def makeShikibetsu(g: js.Dynamic, d: Draft, user: Option[PostingUser]): Future[Unit] = {
    val date = str(DateSalt.window(str(g.unixtime), d.settings.orElse("BBS_ID_RESET", "86400")))
    val identity = d.identity
    val seeds = ShikibetsuSeeds(identity.region, identity.city, identity.asn, identity.userAgent,
      d.settings.orElse("BBS_ID_GROUP", d.form.bbs), date, identity.salt)
    def id(seed: String) = sha(md5(seed))
    val mode = ShikibetsuMode.parse(d.settings.orElse("BBS_ID_SHIKIBETSU", "checked"))
    val shiki = if d.cap.isDefined then Future.successful("★") else mode match {
      case ShikibetsuMode.Checked =>
        for {
          region <- id(seeds.regionSeed)
          city <- id(seeds.citySeed)
          asn <- id(seeds.asnSeed)
        } yield ShikibetsuFormat.checked(identity.country, region, city, asn, md5(seeds.userAgentSeed))
      case _ => Future.successful(ShikibetsuFormat.plain(mode, identity.country, identity.region, identity.city, identity.asn))
    }
    shiki.map { shikibetsu =>
      d.shikibetsu = shikibetsu
      d.form = d.form.copy(name = NameField.withIdentity(d.form.name, PostingUser.nameSuffix(user, d.form.showId, d.cap.isDefined), shikibetsu))
    }
  }

  /** スレ立てなら保存するスレ主情報を返し、返信でスレ主なら名前欄に (主) を付ける。キャップは対象外（docs/トリップ・ID廃止とスレ主表示.md §2.4） */
  private def markNusi(g: js.Dynamic, d: Draft, user: Option[PostingUser], boardSettings: BbsSettings): Future[Option[ThreadOwner]] =
    if d.cap.isDefined then Future.successful(None) else {
      val ip = d.identity.ip.fold("")(KeyIp.of)
      for {
        ipHash <- digest(d, ip)
        accountHash <- digest(d, user.fold("")(_.accountKey))
      } yield {
        val poster = ThreadOwner(ipHash, d.shikibetsu, accountHash)
        if number(g.number) == 1 then Some(poster)
        else {
          if NusiMark.applies(isReply = true, cap = false, noNusi = d.form.noNusi, boardDisabled = boardSettings.checked("BBS_DISABLE_NUSI"),
            threadDisabled = d.settings.checked("BBS_DISABLE_NUSI"), owner = d.threadOwner(), poster = poster) then d.form = d.form.copy(name = d.form.name + NusiMark.suffix)
          None
        }
      }
    }

  private def prepare(g: js.Dynamic, d: Draft): Unit =
    if number(g.number) >= 2 then {
      if d.form.mail.contains("sage") then g.sage = 1
      if int(d.form.key) + d.settings.int("BBS_FORCE_SAGE", "9999999999") < int(g.unixtime) then g.sage = 1
      if d.settings.checked("BBS_LIVE") && js.Math.random() * 10 >= 1 then g.sage = 1
    }

  private def threadCreationLimit(g: js.Dynamic, d: Draft, req: PostingRequest, boardSettings: BbsSettings): Future[Option[ThreadCreationLimit]] = {
    if (number(g.number) != 1) Future.successful(None)
    else {
      val board = BoardId.create(d.form.bbs).fold(_ => reject(PostingError.ForbiddenBoard), identity)
      val key = d.form.key.toLongOption.toRight("").flatMap(ThreadKey.create).fold(_ => reject(PostingError.InvalidKey), identity)
      if (d.cap.isDefined) return Future.successful(None)
      val settings = ThreadCreationSettings.fromSettings(boardSettings).fold(reason => reject(PostingError.InvalidCreationSettings(reason)), identity)
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
        ipHash <- digest(d, context.identity.ip)
        uaHash <- digest(d, context.identity.userAgent)
        networkHash <- digest(d, context.identity.networkGroup)
        titleHash <- digest(d, context.title)
      } yield Some(ThreadCreationLimit(settings.limits, ThreadCreationRequest(ClientIdentity(ipHash, uaHash, networkHash), titleHash)))
    }
  }

  private object CreationRejected {
    def unapply(error: Throwable): Option[ThreadCreationRejection] = ThreadCreationRejection.fromCode(errorText(error).stripPrefix("Error: "))
  }

  private def updateThread(g: js.Dynamic, d: Draft, board: LoadedBoard, e: WorkerEnv, creationLimit: Option[ThreadCreationLimit], owner: Option[ThreadOwner], userInfo: UserInfo): Future[Unit] =
    guarded(g, d, PostingStage.ThreadWrite, FailureClock.Unixtime) {
      val create = number(g.number) == 1
      d.form = d.form.copy(message = DatLineFormatter.spaceFirstBreak(d.form.message))
      val dat = DatLineFormatter.datLine(d.form.name, clock.datDate(int(g.unixtime)), d.form.message, if create then str(g.subject) else "")
      val posting = ThreadPosting(bbs = Some(d.form.bbs), key = Some(d.form.key), title = Some(str(g.subject)), sage = truth(g.sage),
        maxThreads = Some(d.settings.int("BBS_MAX_MENU_THREAD", "700")), create = create,
        initialSettings = if create then Some(initialSettings(d.settings)) else None, creationLimit = creationLimit, owner = owner)
      ThreadRepository.addRes(board.id, d.form.key, dat, DatLineFormatter.logLine(str(g.number), userInfo.text), posting, e).map(n => g.number = n).recover {
        case CreationRejected(rejection) => reject(PostingError.ThreadCreation(rejection))
      }
    }

  /** スレ立て時に保存する初期スレ設定。キーは JavaScript のオブジェクトと同じ順（整数のキーが先） */
  private def initialSettings(settings: BbsSettings): String = {
    val dictionary = settings.value.toJSDictionary
    js.Object.keys(dictionary.asInstanceOf[js.Object]).map(k => k + "=" + dictionary(k) + "<>").mkString
  }

  private def updateSubback(g: js.Dynamic, d: Draft, e: WorkerEnv): Future[Unit] =
    if number(g.number) > 1 then Future.unit else guarded(g, d, PostingStage.Subback, FailureClock.Unixtime) {
      def body(stored: Option[ArchiveIndexRepository.Stored]): String = stored.fold("")(_.text.split("\n", -1).drop(1).mkString("\n"))
      def save(file: String, value: String, etag: Option[String], label: String): Future[Unit] =
        ArchiveIndexRepository.writeIndex(file, value, etag, e).map { written =>
          if !written then reject(PostingError.ArchiveConflict(label, site(g, d, FailureClock.Unixtime, g.nowTime)))
        }
      val subFile = ArchiveIndexRepository.subjectFile(d.form.bbs, d.form.key)
      val archive = ArchiveIndexRepository.archiveListFile(d.form.bbs)
      for {
        current <- ArchiveIndexRepository.read(subFile, e)
        sub = s"${str(g.unixtime)}\n${d.form.key}<>${d.form.subject}<>${d.form.name}<>${d.shikibetsu}<><>${d.form.message}\n${body(current)}"
        _ <- current match {
          case None =>
            for {
              list <- ArchiveIndexRepository.read(archive, e)
              _ <- save(archive, s"${str(g.unixtime)}\n${d.form.key.take(4)}<>${d.form.key}\n${body(list)}", list.flatMap(_.etag), "kakologList")
              _ <- save(subFile, sub, None, "SubbackFIle")
            } yield ()
          case Some(stored) => save(subFile, sub, stored.etag, "SubbackFIle")
        }
      } yield ()
    }

  private def updateHeadline(g: js.Dynamic, d: Draft, e: WorkerEnv): Future[Unit] =
    if truth(g.sage) then Future.unit else guarded(g, d, PostingStage.Headline, FailureClock.Unixtime) {
      for {
        current <- ArchiveIndexRepository.read(ArchiveIndexRepository.HeadlineFile, e)
        text = new StringBuilder(s"${str(g.unixtime)}\n${d.form.bbs}<>${d.settings.get("BBS_TITLE").getOrElse("undefined")}<>${str(g.unixtime)}<>${d.form.key}<>${d.form.message.take(30)}\n")
        _ = current.foreach(_.text.split("\n", -1).slice(1, 301).foreach { line =>
          val f = trim(line).split("<>", -1)
          if f.length >= 5 && matches(f(0), "^[a-zA-Z0-9\\-_]+$") && f(1).nonEmpty && d.form.bbs != f(0) then text.append(f.take(5).mkString("<>")).append('\n')
        })
        _ <- ArchiveIndexRepository.writeHeadline(text.toString, current.flatMap(_.etag), e)
      } yield ()
    }
}
