package io._3ch.bbs.application.posting

import io._3ch.bbs.application.posting.ports.ArchiveIndexFile
import io._3ch.bbs.domain.posting.{ClientIdentity, FailureClock, FailureSite, GlobalThread, KeyIp, NusiMark, PostFormLimits, PostRouting, PostingContext, PostingError, PostingIdentity, PostingStage, PostingUser, ReplyThread, ThreadOwner, ThreadPosting, ThreadWriteResult}
import io._3ch.bbs.domain.posting.moderation.{ModerationDecision, ModerationInput, ModerationService, ThreadCreationLimit, ThreadCreationNetwork, ThreadCreationRequest, ThreadCreationSettings}
import io._3ch.bbs.external.web.URL
import io._3ch.common.domain.{BbsSettings, BoardId, ThreadKey}
import io._3ch.common.domain.identity.{DateSalt, ShikibetsuFormat, ShikibetsuMode, ShikibetsuSeeds}
import io._3ch.common.domain.posting.{ArchiveIndexText, CapHandle, DatLineFormatter, NameField, PostForm, PostKind, SageRule, UserInfo}
import io._3ch.common.text.JsNumber

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.util.Try

/** bbs.cgi の書き込み。段の並びはこのクラスの post だけで決まる。salt は投稿者の照合値に混ぜる秘密の値 */
final class PostingService(ports: PostingPorts, salt: String) {
  import PostingService.*

  /** 書き込む。拒否は Rejected で返し、規制ルールの読込失敗などの想定外の障害は Future の失敗になる */
  def post(request: PostingRequest): Future[PostingOutcome] =
    (for {
      saltHash <- ports.hasher.sha256Hex(salt)
      checked <- checkRequest(initial(request, saltHash), request)
      read <- readForm(checked, request)
      capped <- processCap(read)
      _ = GlobalThread.postingError(capped.form.key, capped.form.kind == PostKind.NewThread, capped.cap.isDefined).foreach(reject(_, capped))
      (board, boardRead) <- boardSettings(capped)
      threadRead <- threadSettings(boardRead, board)
      identified <- identify(threadRead)
      user <- ports.users.current()
      userInfo = UserInfo("", identified.identity.host, identified.identity.asn, identified.identity.country, identified.identity.region,
        identified.identity.city, identified.identity.deviceInfo, identified.identity.userAgent)
      moderated <- moderate(identified, userInfo)
      sized = PostFormLimits.check(moderated.form, moderated.settings, moderated.number, moderated.cap.exists(_.strong))
        .fold(reject(_, moderated), form => moderated.copy(form = form))
      creationLimit <- threadCreationLimit(sized, request, board.settings)
      named <- makeShikibetsu(sized, user)
      (marked, owner) <- markNusi(named, user, board.settings)
      decided = marked.copy(sage = SageRule.sage(marked.number, marked.form.mail, marked.form.key, marked.unixtime, marked.settings, ports.random.next()))
      written <- updateThread(decided, board, creationLimit, owner, userInfo)
      _ <- updateSubback(written)
      _ <- updateHeadline(written)
    } yield PostingOutcome.Posted(written, headers(written))).recover {
      case Rejected(error, context) => PostingOutcome.Rejected(error, context, headers(context))
    }

  private def initial(request: PostingRequest, saltHash: String): PostingContext = {
    val now = ports.clock.nowMillis()
    val identity = PostingIdentity(request.connectingIp, request.connectingIp.getOrElse("null"), request.userAgent, "", request.deviceInformation,
      request.asn.getOrElse("0000"), request.country.getOrElse("unknown"), request.region.getOrElse("catv?"), request.city.getOrElse(""), saltHash)
    PostingContext(PostForm(), identity, time = now.toString, unixtime = math.floor(now / 1000).toString)
  }

  private def headers(context: PostingContext): Seq[(String, String)] = context.ponCookie.map("Set-Cookie" -> _).toSeq

  private def checkRequest(context: PostingContext, request: PostingRequest): Future[PostingContext] = Future {
    request.validate.foreach(reject(_, context))
    context.copy(identity = context.identity.copy(userAgent = request.browserUserAgent, browserInfo = request.browserInfo, host = request.host))
  }

  private def readForm(context: PostingContext, request: PostingRequest): Future[PostingContext] = request.body().map { bytes =>
    PostingFormDecoder.decode(bytes, request.isMonazilla).flatMap(PostRouting.route(_, context.unixtime)) match {
      case Left((error, form)) => reject(error, context.copy(form = form))
      case Right(form) => context.copy(form = form)
    }
  }

  private def processCap(context: PostingContext): Future[PostingContext] = CapHandle.password(context.form.mail) match {
    case None => Future.successful(context)
    case Some(password) =>
      val hasher = ports.hasher
      val key = if (password.startsWith("!")) Future.successful(password.drop(1)) else hasher.sha256Hex(password + context.identity.salt).flatMap(hasher.sha1Base64)
      key.flatMap(ports.caps.find).map { cap =>
        context.copy(cap = cap, form = cap.fold(context.form)(handle => context.form.copy(name = handle.displayName(context.form.name))))
      }
  }

  private def boardSettings(context: PostingContext): Future[(PostingBoard, PostingContext)] =
    guarded(context, PostingStage.BoardSettings, FailureClock.Time) {
      val board = BoardId.create(context.form.bbs).fold(_ => reject(PostingError.ForbiddenBoard, context), identity)
      ports.settings.boardSettings(board).map(settings => (PostingBoard(board, settings), context.copy(settings = settings)))
    }

  private def threadSettings(context: PostingContext, board: PostingBoard): Future[PostingContext] =
    guarded(context, PostingStage.ThreadDataAndSettings, FailureClock.Unixtime) {
      if (context.settings.checked("BBS_HEISA")) reject(PostingError.Closed, context)
      if (context.form.kind == PostKind.NewThread) ports.threads.datBeforeCreation(board.id, context.form.key).map { dat =>
        if (dat.nonEmpty) reject(PostingError.DuplicateKey, context)
        context.copy(number = 1, title = context.form.subject)
      } else ports.threads.readForReply(board.id, context.form.key).map { snapshot =>
        val thread = ReplyThread.open(snapshot.dat, context.form.bbs, context.form.key).fold(reject(_, context), identity)
        val replying = context.copy(number = thread.number, title = thread.title, settings = context.settings.merged(snapshot.settings), threadOwner = snapshot.owner)
        if (replying.settings.checked("BBS_HEISA")) reject(PostingError.Closed, replying)
        replying
      }
    }

  private def identify(context: PostingContext): Future[PostingContext] = {
    val hasher = ports.hasher
    hasher.sha256Hex(context.identity.ip.getOrElse("null") + context.identity.salt).flatMap(hasher.sha1Base64).map { pon =>
      context.copy(settings = context.settings.updated("BBS_SHIKIBETSU", "off"), ponCookie = Some(ponCookie(pon, context.form.isMonazilla)))
    }
  }

  private def moderate(context: PostingContext, userInfo: UserInfo): Future[PostingContext] = {
    val input = ModerationInput(context.form, userInfo, context.cap.isDefined, context.identity.country, context.number.toDouble, context.settings)
    ModerationService.check(input, ports.rules.load).map {
      case ModerationDecision.Pass(form) => context.copy(form = form)
      case ModerationDecision.Deny(denial, form) => reject(PostingError.Moderated(denial), context.copy(form = form))
    }
  }

  private def threadCreationLimit(context: PostingContext, request: PostingRequest, boardSettings: BbsSettings): Future[Option[ThreadCreationLimit]] =
    if (!context.creating) Future.successful(None)
    else {
      if (BoardId.create(context.form.bbs).isLeft) reject(PostingError.ForbiddenBoard, context)
      if (context.form.key.toLongOption.toRight("").flatMap(ThreadKey.create).isLeft) reject(PostingError.InvalidKey, context)
      if (context.cap.isDefined) Future.successful(None)
      else {
        val settings = ThreadCreationSettings.fromSettings(boardSettings).fold(reason => reject(PostingError.InvalidCreationSettings(reason), context), identity)
        val network = ThreadCreationNetwork.group(request.country, request.asn, request.region, settings.asOnly)
        for {
          ipHash <- digest(context, canonicalIp(request.connectingIp.getOrElse("")))
          uaHash <- digest(context, request.userAgent)
          networkHash <- digest(context, network)
          titleHash <- digest(context, context.form.subject)
        } yield Some(ThreadCreationLimit(settings.limits, ThreadCreationRequest(ClientIdentity(ipHash, uaHash, networkHash), titleHash)))
      }
    }

  private def makeShikibetsu(context: PostingContext, user: Option[PostingUser]): Future[PostingContext] = {
    val identity = context.identity
    val date = DateSalt.window(context.unixtime, context.settings.orElse("BBS_ID_RESET", "86400")).toString
    val seeds = ShikibetsuSeeds(identity.region, identity.city, identity.asn, identity.userAgent, context.settings.orElse("BBS_ID_GROUP", context.form.bbs), date, identity.salt)
    val hasher = ports.hasher
    def id(seed: String): Future[String] = hasher.sha256Hex(seed).flatMap(hasher.sha1Base64)
    val shikibetsu = if (context.cap.isDefined) Future.successful("★") else ShikibetsuMode.parse(context.settings.orElse("BBS_ID_SHIKIBETSU", "checked")) match {
      case ShikibetsuMode.Checked =>
        for {
          region <- id(seeds.regionSeed)
          city <- id(seeds.citySeed)
          asn <- id(seeds.asnSeed)
          userAgent <- hasher.sha256Hex(seeds.userAgentSeed)
        } yield ShikibetsuFormat.checked(identity.country, region, city, asn, userAgent)
      case mode => Future.successful(ShikibetsuFormat.plain(mode, identity.country, identity.region, identity.city, identity.asn))
    }
    shikibetsu.map { value =>
      val suffix = PostingUser.nameSuffix(user, context.form.showId, context.cap.isDefined)
      context.copy(shikibetsu = value, form = context.form.copy(name = NameField.withIdentity(context.form.name, suffix, value)))
    }
  }

  /** スレ立てなら保存するスレ主を返し、>>1 を含めスレ主なら名前欄に (主) を付ける。キャップは対象外（docs/トリップ・ID廃止とスレ主表示.md §2.4） */
  private def markNusi(context: PostingContext, user: Option[PostingUser], boardSettings: BbsSettings): Future[(PostingContext, Option[ThreadOwner])] =
    if (context.cap.isDefined) Future.successful((context, None))
    else for {
      ipHash <- digest(context, context.identity.ip.fold("")(KeyIp.of))
      accountHash <- digest(context, user.fold("")(_.accountKey))
    } yield {
      val poster = ThreadOwner(ipHash, context.shikibetsu, accountHash)
      val owner = if (context.creating) Some(poster) else context.threadOwner()
      val marked = NusiMark.applies(cap = false, noNusi = context.form.noNusi, boardDisabled = boardSettings.checked("BBS_DISABLE_NUSI"),
        threadDisabled = context.settings.checked("BBS_DISABLE_NUSI"), owner = owner, poster = poster)
      val named = if (marked) context.copy(form = context.form.copy(name = context.form.name + NusiMark.suffix)) else context
      (named, Option.when(context.creating)(poster))
    }

  private def updateThread(context: PostingContext, board: PostingBoard, creationLimit: Option[ThreadCreationLimit], owner: Option[ThreadOwner],
                           userInfo: UserInfo): Future[PostingContext] = {
    val spaced = context.copy(form = context.form.copy(message = DatLineFormatter.spaceFirstBreak(context.form.message)))
    guarded(spaced, PostingStage.ThreadWrite, FailureClock.Unixtime) {
      val form = spaced.form
      val create = spaced.creating
      val dat = DatLineFormatter.datLine(form.name, ports.clock.datDate(JsNumber.parseInt(spaced.unixtime)), form.message, if (create) spaced.title else "")
      val posting = ThreadPosting(bbs = Some(form.bbs), key = Some(form.key), title = Some(spaced.title), sage = spaced.sage,
        maxThreads = Some(spaced.settings.int("BBS_MAX_MENU_THREAD", "700")), create = create,
        initialSettings = Option.when(create)(spaced.settings.toThreadSettings), creationLimit = creationLimit, owner = owner)
      ports.threads.addRes(board.id, form.key, dat, DatLineFormatter.logLine(spaced.number.toString, userInfo.text), posting).map {
        case ThreadWriteResult.Written(number) => spaced.copy(number = number)
        case ThreadWriteResult.Refused(rejection) => reject(PostingError.ThreadRefused(rejection, site(spaced, FailureClock.Unixtime, spaced.time)), spaced)
        case ThreadWriteResult.CreationLimited(rejection) => reject(PostingError.ThreadCreation(rejection), spaced)
      }
    }
  }

  private def updateSubback(context: PostingContext): Future[Unit] =
    if (context.number > 1) Future.unit
    else guarded(context, PostingStage.Subback, FailureClock.Unixtime) {
      val form = context.form
      val index = ArchiveIndexFile.ThreadIndex(form.bbs, form.key)
      val bands = ArchiveIndexFile.BandList(form.bbs)
      def save(file: ArchiveIndexFile, value: String, etag: Option[String], label: String): Future[Unit] =
        ports.archive.write(file, value, etag).map { written =>
          if (!written) reject(PostingError.ArchiveConflict(label, site(context, FailureClock.Unixtime, UnsetTime)), context)
        }
      for {
        current <- ports.archive.read(index)
        entry = ArchiveIndexText.threadIndex(context.unixtime, form.key, form.subject, form.name, context.shikibetsu, form.message, current.map(_.text))
        _ <- current match {
          case None =>
            for {
              list <- ports.archive.read(bands)
              _ <- save(bands, ArchiveIndexText.bandList(context.unixtime, form.key, list.map(_.text)), list.flatMap(_.etag), "kakologList")
              _ <- save(index, entry, None, "SubbackFIle")
            } yield ()
          case Some(stored) => save(index, entry, stored.etag, "SubbackFIle")
        }
      } yield ()
    }

  private def updateHeadline(context: PostingContext): Future[Unit] =
    if (context.sage) Future.unit
    else guarded(context, PostingStage.Headline, FailureClock.Unixtime) {
      val form = context.form
      for {
        current <- ports.archive.readHeadline()
        text = ArchiveIndexText.headline(context.unixtime, form.bbs, context.settings.get("BBS_TITLE"), form.key, form.message, current.map(_.text))
        _ <- ports.archive.writeHeadline(text, current.flatMap(_.etag))
      } yield ()
    }

  private def digest(context: PostingContext, value: String): Future[String] =
    if (value.isEmpty) Future.successful("") else ports.hasher.sha256Hex(value + ":" + context.identity.salt)

  /** 段の途中の例外を内部エラーにする。時刻は BoardSettings・スレの読込・書込の段だけ受付時刻を出し、ほかは未設定（KB-39） */
  private def guarded[A](context: PostingContext, stage: PostingStage, clock: FailureClock)(f: => Future[A]): Future[A] =
    Future.unit.flatMap(_ => f).recover {
      case e if !e.isInstanceOf[Rejected] =>
        val time = if (clock == FailureClock.Time || stage == PostingStage.ThreadDataAndSettings || stage == PostingStage.ThreadWrite) context.time else UnsetTime
        reject(PostingError.Internal(stage, site(context, clock, time), causeText(e)), context)
    }
}

object PostingService {
  /** 段の途中で拒否したときの理由とその時点の状態。post の中だけで使う */
  private final case class Rejected(error: PostingError, context: PostingContext) extends RuntimeException

  /** 書き込み先の板と、スレ設定で上書きする前の板設定 */
  private final case class PostingBoard(id: BoardId, settings: BbsSettings)

  /** 内部エラーの文言で、受付時刻を持たない段が出す時刻（KB-39） */
  private val UnsetTime = "undefined"

  private def reject(error: PostingError, context: PostingContext): Nothing = throw Rejected(error, context)

  private def site(context: PostingContext, clock: FailureClock, time: String): FailureSite =
    FailureSite(clock, time, context.form.bbs, context.form.key, context.number.toString)

  private def causeText(e: Throwable): String = e match {
    case x: js.JavaScriptException => String.valueOf(x.exception)
    case other => other.toString
  }

  private def ponCookie(pon: String, monazilla: Boolean): String =
    s"PON=$pon; Max-Age=63072000; path=/; ${if (!monazilla) "Secure;" else ""} HttpOnly"

  /** スレ立て規制に使う IP。IPv6 は URL の hostname で正規化する */
  private def canonicalIp(ip: String): String =
    if (ip.contains(":")) Try(new URL(s"http://[$ip]/").hostname).getOrElse(ip.toLowerCase) else ip
}
