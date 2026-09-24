package ch3s.bbs.handler

import ch3s.bbs.external.cloudflare.*
import ch3s.bbs.external.web.{Request, Response}
import ch3s.common.routes.RouteTable
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.concurrent.Future

object PublicRoutes {
  private def str(v: js.Any): String = js.Dynamic.global.String(v).asInstanceOf[String]
  private def truth(v: js.Any): Boolean = js.Dynamic.global.Boolean(v).asInstanceOf[Boolean]
  private def trim(v: String): String = v.asInstanceOf[js.Dynamic].trim().asInstanceOf[String]
  private def error(e: Throwable): String = e match {
    case x: js.JavaScriptException => str(x.exception.asInstanceOf[js.Any])
    case _ => e.toString
  }
  private def notFound(path: String): Response = Responses.response(PublicText.respond404(path), 404, js.Dictionary("content-type" -> "text/html;charset=UTF-8"))
  private def failed(message: String): Response = Responses.response(PublicText.respond500(message), 500, js.Dictionary("content-type" -> "text/html;charset=UTF-8"))
  private def board(e: WorkerEnv, bbs: String): BoardDoStub = e.BOARD_DO.get(e.BOARD_DO.idFromName(bbs)).asInstanceOf[BoardDoStub]
  private def validHead(f: Array[String]): Boolean = f.length >= 5 && f(0).matches("^[a-zA-Z0-9\\-_]+$") && f(1).nonEmpty
  private def read(e: WorkerEnv, file: String): Future[Option[String]] = e.ch3www.get(file).toFuture.flatMap(o => if o == null then Future.successful(None) else o.text().toFuture.map(Some(_)))
  private def date(s: String, parse: Boolean = false): js.Date = new js.Date((if parse then js.Dynamic.global.parseInt(s).asInstanceOf[Double] else js.Dynamic.global.Number(s).asInstanceOf[Double]) * 1000)
  private def guard(path: String)(f: => Future[Response]): Future[Response] = Future.unit.flatMap(_ => f).recover { case e => failed(s"$path Error -> ${error(e)}") }

  def fetch(r: Request, e: WorkerEnv, ctx: js.Dynamic): js.Promise[Response] = {
    val url = js.Dynamic.newInstance(js.Dynamic.global.URL)(r.url)
    val path = str(url.pathname)
    def h(name: String): String = Option(r.headers.get(name)).getOrElse("")
    def cached(work: => Future[Response]): Future[Response] = {
      val cache = js.Dynamic.global.caches.default
      val key = js.Dynamic.newInstance(js.Dynamic.global.Request)(url.toString(), r)
      cache.`match`(key).asInstanceOf[js.Promise[js.Dynamic]].toFuture.flatMap { hit =>
        if truth(hit) then Future.successful(hit.asInstanceOf[Response]) else work.map { response =>
          val raw = response.asInstanceOf[js.Dynamic]
          if raw.status.asInstanceOf[Int] == 200 then ctx.waitUntil(cache.put(key, raw.applyDynamic("clone")()))
          response
        }
      }
    }
    val result: Future[Response] = if path == "/admin" || path.startsWith("/admin/") then Admin.handle(r, e).toFuture
    else if path == "/test/sakubbs.cgi/25" then Future.successful(Responses.plain("sakubbs.cgi v25", "plain"))
    else if new js.RegExp("^/test/(sub)?bbs.cgi").test(path) then Posting.entry(r, e).toFuture
    else if path == "/auth.js" then Future.successful(Responses.response("/* test */", headers = js.Dictionary("content-type" -> "application/javascript;charset=UTF-8")))
    else if path == "/bbsmenu.html" then
      read(e, "bbsmenu.html").flatMap {
        case Some(custom) => Future.successful(Responses.plain(custom, "html", "Shift_JIS", extra = js.Dictionary("cache-control" -> "no-store")))
        case None => cached { guard("/bbsmenu.html") {
          // v5 captures `lastModified = new Date()` before the R2 fetch below (main.js); mirror that timing.
          val fallback = new js.Date()
          read(e, "headline.txt").map {
            case None => notFound("/bbsmenu.html")
            case Some(text) =>
              val rows = text.split("\n", -1)
              val modified = if rows.length > 1 then date(rows(0), true) else fallback
              val links = if rows.length <= 1 then "" else rows.drop(1).map(row => trim(row).split("<>", -1)).filter(validHead).map(f => s"<A HREF=https://www.3chbbs.com/${f(0)}/>${f(1)}</A><br>\n").mkString
              val html = PublicText.menuHead + links + PublicText.menuFoot(modified.asInstanceOf[js.Dynamic].toLocaleString("ja-JP").asInstanceOf[String])
              Responses.plain(html, "html", "Shift_JIS", modified = modified, since = h("if-modified-since"), extra = js.Dictionary("max-age" -> "60", "s-maxage" -> "60"))
          }
        }}
      } else if path == "/headline/subject.txt" then guard("headline.txt") {
      read(e, "headline.txt").flatMap {
        case None => Future.successful(notFound("headline.txt"))
        case Some(text) =>
          val rows = text.split("\n", -1)
          val modified = if rows.length > 1 then date(rows(0), true) else new js.Date()
          val lines = rows.drop(1).map(row => trim(row).split("<>", -1)).filter(validHead)
          lines.foldLeft(Future.successful("")) { (acc, f) => acc.flatMap(s => board(e, f(0)).getRemoved().toFuture.map(removed => s + (if removed.contains(f(3)) then "" else f.take(5).mkString("<>") + "\n"))) }.map(text => Responses.plain(text, "plain", modified = modified, extra = js.Dictionary("cache-control" -> "no-store")))
      }
    } else {
      val route = RouteTable.publicPatterns.iterator.map { (kind, regex) => (kind, new js.RegExp(regex).exec(path)) }.find(_._2 != null)
      route match {
        case None => Future.successful(notFound(path))
        case Some((kind, matched)) =>
          val bbs = matched(1).get
          kind match {
            case "dat" =>
              val key = matched(2).get
              val canonical = s"/$bbs/dat/$key.dat"
              guard(canonical) {
                val stub = e.THREAD_DO.get(e.THREAD_DO.idFromName(s"$bbs/$key")).asInstanceOf[js.Dynamic]
                stub.getPublicData(bbs, key).asInstanceOf[js.Promise[js.Dynamic]].toFuture.map { data =>
                  val dat = str(data.dat)
                  if !truth(data.dat) then notFound(canonical) else {
                    var modified: js.Date = if truth(data.modifiedAt) then new js.Date(data.modifiedAt.asInstanceOf[Double]) else null
                    val last = trim(dat).split("\n", -1).last.split("<>", -1).lift(2).getOrElse("")
                    val dateMatch = new js.RegExp("(\\d{4}/\\d{2}/\\d{2}\\([日月火水木金土]\\) \\d{2}:\\d{2}:\\d{2})").exec(last)
                    if dateMatch != null && modified == null then modified = new js.Date(dateMatch(1).get)
                    val edited = truth(data.edited)
                    Responses.plain(dat, "plain", "Shift_JIS", if edited then "" else h("range"), if modified == null then new js.Date() else modified, if edited then "" else h("if-modified-since"), js.Dictionary("cache-control" -> "no-store", "s-maxage" -> "1"))
                  }
                }
              }
            case "subject" => guard(s"/$bbs/subject.txt") {
              board(e, bbs).getSubject().toFuture.map { text =>
                val rows = text.split("\n", -1).map(trim).filter(_.nonEmpty)
                Responses.plain(rows.map(_ + "\n").mkString, "plain", "Shift_JIS", since = h("if-modified-since"), extra = js.Dictionary("max-age" -> "5", "s-maxage" -> "1"))
              }
            }
            case "archive" | "archives" =>
              val single = kind == "archive"
              val prefix = if single then matched(2).get + "/" else ""
              val canonical = s"/$bbs/kako/${prefix}subject.txt"
              def work = guard(canonical) {
                val removedF = if single then board(e, bbs).getRemoved().toFuture else Future.successful(js.Array[String]())
                removedF.flatMap { removed => read(e, s"$bbs/thread/${prefix}subject.txt").map {
                  case None => notFound(canonical)
                  case Some(text) =>
                    val rows = text.split("\n", -1)
                    val modified = if rows.length > 1 then date(rows(0)) else new js.Date()
                    val fields = rows.drop(1).map(row => trim(row).split("<>", -1))
                    val body = if single then fields.filter(f => f.length >= 6 && f(0).matches("^[0-9]{10}$") && !removed.contains(f(0))).map(_.take(6).mkString("<>") + "\n").mkString
                      else fields.filter(f => f.length >= 2 && f(0).matches("^[0-9]{4}$") && f(1).matches("^[0-9]{10}$")).map(_.take(2).mkString("<>") + "\n").mkString
                    Responses.plain(body, "plain", modified = modified, since = if single then "" else h("if-modified-since"), extra = if single then js.Dictionary("cache-control" -> "no-store") else js.Dictionary("max-age" -> "86400", "s-maxage" -> "86400"))
                }}
              }
              if single then work else cached(work)
            case "head" | "settings" | "board" =>
              val file = if kind == "head" then "head.txt" else "SETTING.TXT"
              guard(s"/$bbs/$file") {
                read(e, s"$bbs/$file").map { stored =>
                  val text = stored.getOrElse(if kind == "head" then "" else PostingText.settingTxtDefault)
                  val settings = if kind == "head" then text else text.replace("<DIR_NAME>", bbs)
                  if kind != "board" then Responses.plain(settings, "plain", "Shift_JIS") else {
                    val title = trim(settings).split("\n", -1).map(_.split("=", -1)).find(_(0) == "BBS_TITLE").map(f => trim(f.lift(1).getOrElse(""))).getOrElse(bbs + "＠3ch掲示板")
                    Responses.plain(PublicText.board(title, bbs), "html", "Shift_JIS")
                  }
                }
              }
            case _ => Future.successful(notFound(path))
          }
      }
    }
    result.toJSPromise
  }
}
