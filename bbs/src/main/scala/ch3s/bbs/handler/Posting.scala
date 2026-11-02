package ch3s.bbs.handler

import ch3s.bbs.external.cloudflare.*
import ch3s.bbs.external.web.{Request, Response}
import ch3s.bbs.hash.WebHash
import ch3s.bbs.repository.BoardRepository
import ch3s.bbs.util.SjisEncoding
import ch3s.common.crypt.UnixCrypt
import ch3s.common.domain.BbsSettings
import ch3s.common.hash.{HexToBinary, Md5}

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.scalajs.js.typedarray.Uint8Array

object Posting {
  private case object Rejected extends RuntimeException
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
  private def deny(g: js.Dynamic, message: String): Nothing = { g.error = message; throw Rejected }
  private def sha(s: String): Future[String] = WebHash.sha1Base64(s).toFuture
  private def hash(s: String): Future[String] = WebHash.hash("SHA-256", s).toFuture
  private def md5(s: String): String = Md5.md5(s)
  private def thread(g: js.Dynamic, e: WorkerEnv): ThreadDoStub = e.THREAD_DO.get(e.THREAD_DO.idFromName(s"${str(g.form.bbs)}/${str(g.form.key)}")).asInstanceOf[ThreadDoStub]
  private def errorText(e: Throwable): String = e match {
    case x: js.JavaScriptException => str(x.exception.asInstanceOf[js.Any])
    case _ => e.toString
  }
  private def guarded(g: js.Dynamic, stage: String, description: String, clock: String)(f: => Future[Unit]): Future[Unit] =
    Future.unit.flatMap(_ => f).recover { case Rejected => throw Rejected; case e =>
      deny(g, s"$stage error. $description($clock:${str(if clock == "time" || stage == "getThreadDataAndThreadSettings" || stage == "updateThreadFile" then g.time else g.nowTime)} bbs:${str(g.form.bbs)} key:${str(g.form.key)} number:${str(g.number)} errmsg:$stage->${errorText(e)})<br>このメッセージをコピペして、運用情報板で報告していただけるとありがたいです。")
    }

  def entry(request: Request, env: WorkerEnv): js.Promise[Response] = {
    val rawEnv = env.asInstanceOf[js.Dynamic]
    val cf = request.cf.getOrElse(js.Dynamic.literal())
    def header(name: String): String = Option(request.headers.get(name)).getOrElse("")
    def fallback(v: js.Any, value: String): js.Any = if v == null || js.isUndefined(v) then value else v
    val g = js.Dynamic.literal(
      version = "2025/7/31", error = "", time = str(js.Date.now()), unixtime = str(math.floor(js.Date.now() / 1000)),
      form = js.Dynamic.literal(FROM = "", mail = "", MESSAGE = "", subject = "", bbs = "", key = "", time = "", submit = ""),
      cookies = js.Dynamic.literal(HAP = "", D = "", U = "", PON = "", time = "", PIN = "", cert = ""),
      host = request.headers.get("CF-Connecting-IP"), ip = request.headers.get("CF-Connecting-IP"), userAgent = header("user-agent"), browserInfo = "", deviceInfo = header("x-ch3-device-information"),
      asn = fallback(cf.asn, "0000"), country = fallback(cf.country, "unknown"), ken = fallback(cf.region, "catv?"), city = fallback(cf.city, ""),
      uid = "", userInfo = "", shikibetsuId = "", id = "", isIPv6 = 0, isMonazilla = 0, isSJIS = 0, idnotane = "",
      md5Salt = md5(str(rawEnv.salt)), tripKey = "", tripString = "", cap = 0, strongCap = 0, sage = 0,
      path = "PATH", logPath = "LOGPATH", logFile = "LOGDAT", logData = "", subject = "", number = 0,
      settings = js.Dynamic.literal(), boardSettings = js.Dynamic.literal(), headers = js.Dictionary.empty[String]
    )
    (for {
      _ <- Future { checkRequest(g, request) }
      _ <- readForm(g, request)
      _ <- processCap(g, env)
      _ <- processTrip(g)
      _ = { g.path = str(g.form.bbs) + "/"; g.logPath = str(g.path) + "thread/" + str(g.form.key).take(4) + "/"; g.logFile = str(g.logPath) + str(g.form.key) + ".cgi" }
      _ <- boardSettings(g, env)
      _ <- threadSettings(g, env)
      _ <- identify(g, request)
      _ = g.userInfo = s"UID:${str(g.uid)} IP:${str(g.host)} AS${str(g.asn)}-${str(g.country)},${str(g.ken)},${str(g.city)}Device:${str(g.deviceInfo)} UA:${str(g.userAgent)}"
      allowed <- Moderation.check(g, env).toFuture
      _ = if allowed == 0 then throw Rejected
      _ = checkSizes(g)
      _ <- makeId(g)
      _ = prepare(g)
      _ <- updateThread(g, env)
      _ <- updateSubback(g, env)
      _ <- updateHeadline(g, env)
    } yield respond(g, success = true)).recover { case Rejected => respond(g, success = false) }.toJSPromise
  }

  private def respond(g: js.Dynamic, success: Boolean): Response = {
    val charset = if truth(g.isSJIS) then "Shift_JIS" else "UTF-8"
    val headers = g.headers.asInstanceOf[js.Dictionary[String]]
    if success then { headers("x-threadkey") = str(g.form.key); headers("x-resnum") = str(g.number) }
    val html = if success then PostingPages.respondEndHtml(g, charset) else PostingPages.respondError(g, charset)
    RendererShell.legacyReact(html, encoding = charset, extra = headers)
  }

  private def checkRequest(g: js.Dynamic, r: Request): Unit = {
    def h(name: String) = Option(r.headers.get(name)).getOrElse("")
    if r.method != "POST" then deny(g, "POST メソッドを使ってください。<br>\nRecieve GET METHOD")
    if !h("content-type").contains("application/x-www-form-urlencoded") then deny(g, "送信された内容が破損しています。->" + h("content-type") + "<br>\nBroken request body. ")
    if !matches(h("referer"), "^https?://www\\.3chbbs\\.com") && !matches(h("referer"), "^http://127\\.0\\.0\\.1:878[78]") then deny(g, "ERROR: あなた騙されてますよ？->" + h("referer") + "<br>\nReferer is invalid.")
    val ua = str(g.userAgent)
    if ua.length < 7 || ua.length > 384 || matches(ua, "[^ -~]") then deny(g, "ERROR: ブラウザ変ですよん。(ua)<br>UserAgent: " + ua + "<br>\nUserAgent is invalid.")
    g.userAgent = ua + Option(r.headers.get("sec-ch-ua-full-version-list")).getOrElse(h("sec-ch-ua"))
    g.browserInfo = "Accept:" + h("accept") + "Accept-Language:" + h("accept-language") + "Accept-Encoding:" + h("accept-encoding")
    if matches(str(g.userAgent), "^Monazilla/1.00") then g.isMonazilla = 1
    if g.ip != null && str(g.ip).contains(":") then {
      g.isIPv6 = 1
      val parts = str(g.ip).split(":", -1)
      g.idnotane = (0 until 4).map(i => parts.lift(i).getOrElse("undefined")).mkString(":")
    } else g.idnotane = g.ip
    if h("client-ip").nonEmpty then deny(g, "変な情報が送られて来ました。<br>Client_IP: " + h("client-ip"))
    g.host = g.ip
    List("x-forwarded-for", "forwarded", "via").find(name => h(name).nonEmpty && !eq(g.ip, h(name))).foreach(name => g.host = str(g.host) + "<" + h(name) + ">")
  }

  private def readForm(g: js.Dynamic, r: Request): Future[Unit] = r.arrayBuffer().toFuture.map { buffer =>
    val decoder = js.Dynamic.newInstance(js.Dynamic.global.TextDecoder)("utf-8")
    val body = decoder.decode(new Uint8Array(buffer)).asInstanceOf[String]
    if !matches(body, "submit=([^&]+)") then deny(g, "書き込みボタンを押忍べし<br>\nNo submit")
    if !body.toLowerCase.contains("submit=%e6%9b%b8") then {
      g.isSJIS = 1
      if !truth(g.isMonazilla) then deny(g, "書き込みボタンを押忍べし<br>2ch専用ブラウザからの書き込みでこのエラーが出た場合、お使いの専用ブラウザはMonazilla/1.00で始まるUserAgentを使用していません。<br>認証時の問題などもありますので、2ch専用ブラウザではMonazilla/1.00で始まるUserAgentを使用してください。<br>(Monazilla/1.00以外のUserAgentでsubmit=%E6%9B%B8…でない場合にこのエラーが出ます。)<br>\n&apos;Submit&apos; must require &apos;書&apos; with Web browser&apos;s post.")
    }
    body.split("&", -1).foreach { pair =>
      val parts = pair.split("=", -1)
      if parts.length >= 2 && parts(0).nonEmpty then {
        val encoded = parts(1).replace("+", " ")
        var value = if truth(g.isSJIS) then decodeSjis(encoded) else js.URIUtils.decodeURIComponent(encoded)
        for (pattern, replacement) <- List("&(?!#[a-zA-Z0-9]+;)" -> "&amp;", "\"" -> "&quot;", "<" -> "&lt;", ">" -> "&gt;", "'" -> "&apos;", "\t" -> " ", "&#0*1[03];" -> "<br>", "&#[xX]0*[aAdD];" -> "<br>", "\r\n?|\n" -> "<br>", "[\\x00-\\x1F\\x7F]" -> "") do value = replace(value, pattern, replacement)
        g.form.updateDynamic(parts(0))(value)
      }
    }
    g.form.FROM = str(g.form.FROM).replace("★", "☆").replace("◆", "◇").replace("＃", "#")
    g.form.mail = str(g.form.mail).replace("＃", "#")
    val trip = new js.RegExp("^([^#]*?)(?<!&#[xX]?[0-9a-fA-F]{1,8});?#(.+)$").exec(str(g.form.FROM))
    if trip != null then {
      g.form.FROM = trip(1); g.tripKey = trip(2)
      if trip(2).get.length > 1023 then deny(g, "トリップキー長杉！<br>\nYour tripkey must be less than 1023 bytes.")
    }
    val next = new js.RegExp("^!newthread([^\\r\\t\\n\\f\\v<]+)(.*)$").exec(str(g.form.MESSAGE))
    if str(g.form.key).startsWith("9") && next != null then {
      g.form.subject = next(1); g.form.MESSAGE = next(2); g.form.key = ""
    }
    for name <- List("subject", "FROM", "mail", "submit") do g.form.updateDynamic(name)(replace(str(g.form.selectDynamic(name)), " ?<br> ?", ""))
    g.form.bbs = replace(str(g.form.bbs), "[^a-zA-Z0-9_-]", "")
    for name <- List("key", "time") do g.form.updateDynamic(name)(replace(str(g.form.selectDynamic(name)), "\\D", ""))
    if matches(str(g.form.key), "^(924|900)") then g.form.bbs = "924"
    if Set("", "test", "js", "css", "html", "images").contains(str(g.form.bbs)) then deny(g, "板の指定が不正です。<br>\nThe board directory is incorrect...")
    if eq(g.form.MESSAGE, "") then deny(g, "本文がありません！")
    if (eq(g.form.key, "") || !matches(str(g.form.key), "^[0-9]+$")) && eq(g.form.subject, "") then deny(g, "subjectが存在しないかキー情報が不正です。key=" + str(g.form.key) + "<br>\nThe subject is empty and key is wrong.")
    if eq(g.form.time, "") || !matches(str(g.form.time), "^[0-9]+$") then deny(g, "ブラウザ変ですよん。(time)<br>\ntime is empty. time=" + str(g.form.time))
    if !eq(g.form.key, "") then g.form.subject = ""
    if truth(g.form.subject) then g.form.key = g.unixtime
  }

  private def decodeSjis(s: String): String = {
    val bytes = scala.collection.mutable.ArrayBuffer.empty[Int]
    var i = 0
    while i < s.length do
      if s(i) == '%' && i + 2 < s.length && matches(s.substring(i + 1, i + 3), "^[0-9a-fA-F]{2}$") then {
        bytes += Integer.parseInt(s.substring(i + 1, i + 3), 16); i += 3
      } else { bytes += s(i).toInt; i += 1 }
    val u8 = new Uint8Array(bytes.length)
    bytes.indices.foreach(j => u8(j) = bytes(j).toShort)
    SjisEncoding.decode(u8)
  }

  private def processCap(g: js.Dynamic, e: WorkerEnv): Future[Unit] = {
    val pass = str(g.form.mail).split("#", -1).lift(1).filter(_.nonEmpty)
    pass match {
      case None => Future.unit
      case Some(password) =>
        val capPass = if password.startsWith("!") then Future.successful(password.drop(1)) else hash(md5(password) + str(g.md5Salt)).flatMap(sha)
        capPass.flatMap(p => e.CAP.get(p).toFuture).map { result =>
          if truth(result.asInstanceOf[js.Any]) then {
            var handle = str(result.asInstanceOf[js.Any])
            g.cap = 1
            if matches(handle, "★$") then { g.strongCap = 1; handle = replace(handle, "★.*", "", "") }
            g.form.FROM = (if eq(g.form.FROM, "") then "" else str(g.form.FROM) + "＠") + handle + " ★"
          }
        }
    }
  }

  private def processTrip(g: js.Dynamic): Future[Unit] =
    if eq(g.tripKey, "") then Future.unit else {
      val password = {
        val bytes = SjisEncoding.encode(str(g.tripKey))
        val sb = new StringBuilder(bytes.length)
        var k = 0
        while k < bytes.length do { sb.append((bytes(k).toInt & 0xff).toChar); k += 1 }
        sb.toString
      }
      val trip: Future[String] = if password.length >= 12 then
        if password.startsWith("#") || password.startsWith("$") then {
          val matched = new js.RegExp("^#([0-9a-fA-F]{16})([./0-9A-Za-z]{0,2})$").exec(password)
          Future.successful(if matched == null then "???" else UnixCrypt.crypt(HexToBinary.hexToBinary(matched(1).get), (if matched(2).get.isEmpty then ".." else matched(2).get).padTo(2, '.')).takeRight(10))
        } else sha(password).map(_.take(12).replace("+", "."))
      else {
        val from = ":;<=>?@[\\]^_`"
        val to = "ABCDEFGabcdef"
        val salt = replace(password.slice(1, 2) + "H", "[\\x00-\\x20\\x7B-\\xFF]", ".").map(c => if from.indexOf(c) >= 0 then to(from.indexOf(c)) else c)
        Future.successful(UnixCrypt.crypt(password, salt).takeRight(10))
      }
      trip.map(t => { g.tripString = t; g.form.FROM = str(g.form.FROM) + s" </b>◆$t</b>" })
    }

  private def boardSettings(g: js.Dynamic, e: WorkerEnv): Future[Unit] = guarded(g, "getBoardSettings", "不明なエラーが発生しました。", "time") {
    BoardRepository.getBoardSettings(str(g.form.bbs), e).map { boardSettings =>
      g.settings = boardSettings.value.toJSDictionary.asInstanceOf[js.Dynamic]
      g.boardSettings = g.settings
    }
  }
  private def threadSettings(g: js.Dynamic, e: WorkerEnv): Future[Unit] = {
    val stub = thread(g, e)
    guarded(g, "getThreadDataAndThreadSettings", "不明なエラーが発生しました。", "unixtime") {
      if eq(g.settings.BBS_HEISA, "checked") then deny(g, "この板・スレッドは停止されています。")
      if truth(g.form.subject) then stub.getDat().toFuture.map { dat =>
        if dat.nonEmpty then deny(g, "別の人が同時刻にスレッドを立てようとしています。ごめんなさい。")
        g.logData = str(g.form.subject) + "\n"; g.number = 1; g.subject = g.form.subject
      } else {
        val datF = stub.getDat(str(g.form.bbs), str(g.form.key)).toFuture
        val settingsF = stub.getSettings().toFuture
        datF.zip(settingsF).map { (dat, settings) =>
          if dat.isEmpty then deny(g, "該当するスレッドがありません。" + str(g.logFile))
          val lines = dat.split("\n", -1)
          if lines(0).isEmpty then deny(g, "スレッドのログファイルが壊れています。(空です)")
          g.subject = lines(0).split("<>", -1).last
          if !truth(g.subject) then deny(g, "スレッドのログファイルが壊れています。(タイトル取得失敗)")
          g.number = lines.length
          BbsSettings.parse(settings, "<>").value.foreach((k, v) => g.settings.updateDynamic(k)(v))
          if lines.length >= 1000 then deny(g, "このスレッドは1000を超えたのでもう書けません。")
          if eq(g.settings.BBS_HEISA, "checked") then deny(g, "この板・スレッドは停止されています。")
        }
      }
    }
  }
  private def identify(g: js.Dynamic, r: Request): Future[Unit] = {
    g.settings.BBS_SHIKIBETSU = "off"
    Option(r.headers.get("cookie")).getOrElse("").split("[;,]\\s*", -1).foreach { pair =>
      val parts = pair.split("=", -1)
      if parts.length >= 2 && parts(0).nonEmpty && parts(1).nonEmpty then g.cookies.updateDynamic(parts(0))(parts(1))
    }
    hash(md5(str(g.ip)) + str(g.md5Salt)).flatMap(sha).map { pon =>
      g.headers.updateDynamic("Set-Cookie")(s"PON=$pon; Max-Age=63072000; path=/; Domain=.3chbbs.com; ${if !truth(g.isMonazilla) then "Secure;" else ""} HttpOnly")
    }
  }

  private def checkSizes(g: js.Dynamic): Unit = {
    if truth(g.strongCap) then return
    def setting(name: String, fallback: Int): js.Any = {
      val v = g.settings.selectDynamic(name)
      if v == null || js.isUndefined(v) then fallback else v
    }
    for (field, settingName, default, message) <- List(("subject", "BBS_SUBJECT_COUNT", 64, "サブジェクト"), ("FROM", "BBS_NAME_COUNT", 64, "名前"), ("mail", "BBS_MAIL_COUNT", 64, "メールアドレス")) do {
      val len = str(g.form.selectDynamic(field)).length
      val limit = setting(settingName, default)
      if len > number(limit) then deny(g, s"$message が長すぎます！($len/${str(limit)})".replace(" が", "が"))
    }
    val message = str(g.form.MESSAGE)
    if message.startsWith("!ADMIN:") then return
    var limit = setting("BBS_MESSAGE_COUNT", 4096)
    if number(g.number) == 1 && number(limit) < 65536 then limit = 65536
    if message.length > number(limit) then deny(g, s"本文が長すぎます！(${message.length}/${str(limit)})")
    val lines = message.split("<br>", -1).length
    val lineLimit = number(setting("BBS_LINE_NUMBER", 30)) * 2
    if number(g.number) >= 2 && lines > lineLimit then deny(g, s"改行が多すぎます！($lines/${str(lineLimit)})")
    if !truth(g.form.FROM) then g.form.FROM = or(g.settings.BBS_NONAME_NAME, "名無しさん＠３ちゃんねる")
  }

  private def makeId(g: js.Dynamic): Future[Unit] = {
    val group = or(g.settings.BBS_ID_GROUP, str(g.form.bbs))
    val mode = or(g.settings.BBS_ID, "checked")
    val date = str(math.floor((int(g.unixtime) + 32400) / number(or(g.settings.BBS_ID_RESET, "86400"))))
    def id(seed: String, len: Int) = sha(md5(seed + group + date + str(g.md5Salt))).map(_.take(len))
    val level = eq(g.settings.BBS_ID_LV, "checked")
    val idF = if truth(g.cap) then Future.successful("ID:???")
      else if mode == "ip" || mode == "checked" then id(str(g.idnotane), 8).map(s => "ID:" + s + (if level then "10" else ""))
      else if mode == "siberia" then Future.successful("発信元:" + str(g.ip) + (if level then " 10" else ""))
      else Future.successful(if level then "10" else "")
    idF.flatMap { value =>
      g.id = value
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
        g.shikibetsuId = s; g.form.FROM = str(g.form.FROM) + s" </b>($s)<b>"
        if number(g.number) == 1 && truth(g.id) then g.subject = str(g.subject) + s" [${str(g.id)}★]"
      }
    }
  }
  private def prepare(g: js.Dynamic): Unit =
    if number(g.number) >= 2 then {
      if str(g.form.mail).contains("sage") then g.sage = 1
      if int(g.form.key) + int(or(g.settings.BBS_FORCE_SAGE, "9999999999")) < int(g.unixtime) then g.sage = 1
      if eq(g.settings.BBS_LIVE, "checked") && js.Math.random() * 10 >= 1 then g.sage = 1
    }

  private def updateThread(g: js.Dynamic, e: WorkerEnv): Future[Unit] = {
    val stub = thread(g, e)
    guarded(g, "updateThreadFile", "ログファイルに書けませんでした。", "unixtime") {
      val initial: js.UndefOr[String] = if number(g.number) == 1 then js.Object.keys(g.settings.asInstanceOf[js.Object]).map(k => k + "=" + str(g.settings.selectDynamic(k)) + "<>").mkString else js.undefined
      val formatter = js.Dynamic.newInstance(js.Dynamic.global.Intl.DateTimeFormat)("ja-JP", js.Dynamic.literal(year = "numeric", month = "2-digit", day = "2-digit", weekday = "short", hour = "2-digit", minute = "2-digit", second = "2-digit", timeZone = "Asia/Tokyo"))
      val date = formatter.format(new js.Date(int(g.unixtime) * 1000)).asInstanceOf[String]
      g.form.MESSAGE = str(g.form.MESSAGE).asInstanceOf[js.Dynamic].replace("<br>", " <br> ")
      val dat = s"${str(g.form.FROM)}<><>$date ${str(g.id)}<> ${str(g.form.MESSAGE)} <>${if number(g.number) == 1 then str(g.subject) else ""}"
      stub.addRes(dat, s"${str(g.number)}<>${str(g.userInfo)}", js.Dynamic.literal(bbs = g.form.bbs, key = g.form.key, title = g.subject, sage = truth(g.sage), maxThreads = int(or(g.boardSettings.BBS_MAX_MENU_THREAD, "700")), create = (number(g.number) == 1), initialSettings = initial)).toFuture.map(n => g.number = n)
    }
  }

  private def updateSubback(g: js.Dynamic, e: WorkerEnv): Future[Unit] =
    if number(g.number) > 1 then Future.unit else guarded(g, "updateSubback", "不明なエラーが発生しました。", "unixtime") {
      val bucket = e.ch3www.asInstanceOf[js.Dynamic]
      def get(file: String): Future[js.Dynamic] = bucket.get(file).asInstanceOf[js.Promise[js.Dynamic]].toFuture
      def text(obj: js.Dynamic): Future[String] = if obj == null then Future.successful("") else obj.text().asInstanceOf[js.Promise[String]].toFuture.map(_.split("\n", -1).drop(1).mkString("\n"))
      def save(file: String, value: String, etag: js.Any, label: String): Future[Unit] = {
        val condition = if truth(etag) then js.Dynamic.literal(etagMatches = etag) else js.Dynamic.literal(doesNotExist = true)
        bucket.put(file, value, js.Dynamic.literal(onlyIf = condition)).asInstanceOf[js.Promise[js.Dynamic]].toFuture.map { result =>
          if result == null || !truth(result.uploaded) then deny(g, s"updateSubback error. 板飛びそうなので、またの機会にどうぞ。。。(unixtime:${str(g.nowTime)} bbs:${str(g.form.bbs)} key:${str(g.form.key)} number:${str(g.number)} errmsg:updateSubback->${label}_update_conflicted!)")
        }
      }
      val subFile = str(g.logPath) + "subject.txt"
      val archive = str(g.path) + "thread/subject.txt"
      for {
        obj <- get(subFile)
        old <- text(obj)
        sub = s"${str(g.unixtime)}\n${str(g.form.key)}<>${str(g.form.subject)}<>${str(g.form.FROM)}<>${str(g.shikibetsuId)}<>${str(g.id)}<>${str(g.form.MESSAGE)}\n$old"
        _ <- if obj == null then
          for {
            arch <- get(archive)
            list <- text(arch)
            _ <- save(archive, s"${str(g.unixtime)}\n${str(g.form.key).take(4)}<>${str(g.form.key)}\n$list", if arch == null then js.undefined else arch.etag, "kakologList")
            _ <- save(subFile, sub, js.undefined, "SubbackFIle")
          } yield ()
        else save(subFile, sub, obj.etag, "SubbackFIle")
      } yield ()
    }

  private def updateHeadline(g: js.Dynamic, e: WorkerEnv): Future[Unit] =
    if truth(g.sage) then Future.unit else guarded(g, "updateHeadline", "不明なエラーが発生しました。", "unixtime") {
      val bucket = e.ch3www.asInstanceOf[js.Dynamic]
      for {
        obj <- bucket.get("headline.txt").asInstanceOf[js.Promise[js.Dynamic]].toFuture
        old <- if obj == null then Future.successful("") else obj.text().asInstanceOf[js.Promise[String]].toFuture
        text = new StringBuilder(s"${str(g.unixtime)}\n${str(g.form.bbs)}<>${str(g.boardSettings.BBS_TITLE)}<>${str(g.unixtime)}<>${str(g.form.key)}<>${str(g.form.MESSAGE).take(30)}\n")
        _ = if obj != null then old.split("\n", -1).drop(1).take(300).foreach { line =>
          val f = trim(line).split("<>", -1)
          if f.length >= 5 && matches(f(0), "^[a-zA-Z0-9\\-_]+$") && f(1).nonEmpty && !eq(g.form.bbs, f(0)) then text.append(f.take(5).mkString("<>")).append('\n')
        }
        _ <- bucket.put("headline.txt", text.toString, js.Dynamic.literal(onlyIf = js.Dynamic.literal(etagMatches = (if obj == null then js.undefined else obj.etag)))).asInstanceOf[js.Promise[js.Any]].toFuture
      } yield ()
    }
}
