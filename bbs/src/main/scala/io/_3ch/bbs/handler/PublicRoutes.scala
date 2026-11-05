package io._3ch.bbs.handler

import io._3ch.bbs.external.cloudflare.*
import io._3ch.bbs.external.web.{Request, Response}
import io._3ch.bbs.domain.posting.GlobalThread
import io._3ch.common.routes.RouteTable
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.concurrent.Future

//TODO: 3chiko.html spaをhonoxのssr化（3chiko.htmlはwebclient=3chiko場合のみ）
//TODO: エンドポイント分離

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
  private def read(e: WorkerEnv, file: String): Future[Option[String]] = e.ch3www.get(file).toFuture.flatMap(o => if o == null then Future.successful(None) else o.text().toFuture.map(Option(_)))
  private def date(s: String, parse: Boolean = false): js.Date = new js.Date((if parse then js.Dynamic.global.parseInt(s).asInstanceOf[Double] else js.Dynamic.global.Number(s).asInstanceOf[Double]) * 1000)
  private def guard(path: String)(f: => Future[Response]): Future[Response] = Future.unit.flatMap(_ => f).recover { case e => failed(s"$path Error -> ${error(e)}") }
  /** 3chiko.html本体をWorkers Static Assets(env.ASSETS)経由で取得する(public/3chiko.html、src/3chiko.js参照)。 */
  private def view3chiko(e: WorkerEnv, url: js.Dynamic, r: Request): Future[Response] = {
    val assetUrl = js.Dynamic.newInstance(js.Dynamic.global.URL)("/3chiko.html", url)
    val assetRequest = js.Dynamic.newInstance(js.Dynamic.global.Request)(assetUrl, r)
    e.ASSETS.fetch(assetRequest.asInstanceOf[Request]).toFuture
  }

  def fetch(r: Request, e: WorkerEnv, ctx: js.Dynamic): js.Promise[Response] = {
    val url = js.Dynamic.newInstance(js.Dynamic.global.URL)(r.url)
    val path = str(url.pathname)
    val oldDomain = str(url.hostname) == "www.3chbbs.com"
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
    val result: Future[Response] = if path == "/admin" || path.startsWith("/admin/") then AdminHandler.handle(r, e).toFuture
    // トップページ (3chiko.html)
    else if path == "/" then view3chiko(e, url, r)
    // read.cgi スレ表示 (3chiko.html)
    else if new js.RegExp("^/test/read\\.cgi/[a-zA-Z0-9\\-_]+/[0-9]{10}/$").test(path) then view3chiko(e, url, r)
    // 過去ログ倉庫 (3chiko.html)。subject.txt系APIはRouteTable経由で別途下(archive/archives)で処理する
    else if new js.RegExp("^/[a-zA-Z0-9\\-_]+/kako(/.*)?$").test(path) && !path.endsWith("/subject.txt") then view3chiko(e, url, r)
    else if new js.RegExp("^/test/(sub)?bbs.cgi").test(path) then PostingHandler.entry(r, e).toFuture
    else if path == "/auth.js" then Future.successful(Responses.response("/* test */", headers = js.Dictionary("content-type" -> "application/javascript;charset=UTF-8")))
    else if path == "/bbsmenu.html" then
      read(e, "bbsmenu.html").flatMap {
        case Some(custom) => Future.successful(RendererShell.legacyHtml(custom, extra = js.Dictionary("cache-control" -> "no-store")))
        case None => cached { guard("/bbsmenu.html") {
          // v5 captures `lastModified = new Date()` before the R2 fetch below (main.js); mirror that timing.
          val fallback = new js.Date()
          read(e, "headline.txt").map {
            case None => notFound("/bbsmenu.html")
            case Some(text) =>
              val rows = text.split("\n", -1)
              val modified = if rows.length > 1 then date(rows(0), true) else fallback
              val boards = rows.drop(1).map(row => trim(row).split("<>", -1)).filter(validHead).map(f => f(0) -> f(1)).toSeq
              val page = BbsMenuPage.render(boards, modified.asInstanceOf[js.Dynamic].toLocaleString("ja-JP").asInstanceOf[String])
              RendererShell.legacyReact(page, modified = modified, since = h("if-modified-since"), extra = js.Dictionary("max-age" -> "60", "s-maxage" -> "60"))
          }
        }}
      } else if path == "/headline/subject.txt" then guard("headline.txt") {
      read(e, "headline.txt").flatMap {
        case None => Future.successful(notFound("headline.txt"))
        case Some(text) =>
          val rows = text.split("\n", -1)
          val modified = if rows.length > 1 then date(rows(0), true) else new js.Date()
          val lines = rows.drop(1).map(row => trim(row).split("<>", -1)).filter(validHead)
          lines.foldLeft(Future.successful("")) { (acc, f) => acc.flatMap(s => board(e, f(0)).getRemoved().toFuture.map(removed => s"$s${if removed.contains(f(3)) then "" else s"${f.take(5).mkString("<>")}\n"}")) }.map(text => Responses.plain(text, "plain", modified = modified, extra = js.Dictionary("cache-control" -> "no-store")))
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
              // 全板共通スレ(924/900始まり)はどの板のURLからでも板924のThread DOを読む。
              val owner = if GlobalThread.kindOf(key).isDefined then GlobalThread.board else bbs
              guard(canonical) {
                val stub = e.THREAD_DO.get(e.THREAD_DO.idFromName(s"$owner/$key")).asInstanceOf[js.Dynamic]
                stub.getPublicData(owner, key).asInstanceOf[js.Promise[js.Dynamic]].toFuture.map { data =>
                  val dat = str(data.dat)
                  if !truth(data.dat) then {
                    if oldDomain && key == DomainMigration.key then
                      Responses.plain(DomainMigration.dat(bbs), "plain", "Shift_JIS", h("range"), new js.Date(DomainMigration.modifiedMillis), h("if-modified-since"), js.Dictionary("cache-control" -> "no-store"))
                    else notFound(canonical)
                  } else {
                    var modified: js.Date = if truth(data.modifiedAt) then new js.Date(data.modifiedAt.asInstanceOf[Double]) else null
                    val last = trim(dat).split("\n", -1).last.split("<>", -1).lift(2).getOrElse("")
                    val dateMatch = new js.RegExp("(\\d{4}/\\d{2}/\\d{2}\\([日月火水木金土]\\) \\d{2}:\\d{2}:\\d{2})").exec(last)
                    if dateMatch != null && modified == null then modified = new js.Date(dateMatch(1).get)
                    val edited = truth(data.edited)
                    Responses.plain(if oldDomain then DomainMigration.annotateDat(dat, bbs, key) else dat, "plain", "Shift_JIS", if edited || oldDomain then "" else h("range"), if modified == null then new js.Date() else modified, if edited || oldDomain then "" else h("if-modified-since"), js.Dictionary("cache-control" -> "no-store", "s-maxage" -> "1", "Accept-Ranges" -> (if oldDomain then "none" else "bytes")))
                  }
                }
              }
            case "subject" => guard(s"/$bbs/subject.txt") {
              // 全板共通スレ(板924の一覧)を 924 → 900 の順で先頭に置き、板固有のスレを続ける。
              def rows(text: String): Seq[String] = text.split("\n", -1).map(trim).filter(_.nonEmpty).toSeq
              val ownF = board(e, bbs).getSubject().toFuture
              val globalF = if bbs == GlobalThread.board then ownF else board(e, GlobalThread.board).getSubject().toFuture
              ownF.zip(globalF).map { (own, global) =>
                val merged = GlobalThread.mergeSubject(rows(global), if bbs == GlobalThread.board then Nil else rows(own))
                val body = merged.map(_ + "\n").mkString
                Responses.plain(if oldDomain then DomainMigration.subject(body) else body, "plain", "Shift_JIS", since = h("if-modified-since"), extra = js.Dictionary("max-age" -> "5", "s-maxage" -> "1"))
              }
            }
            case "archive" | "archives" =>
              val single = kind == "archive"
              val prefix = if single then s"${matched(2).get}/" else ""
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
            case "head" | "settings" =>
              val file = if kind == "head" then "head.txt" else "SETTING.TXT"
              guard(s"/$bbs/$file") {
                read(e, s"$bbs/$file").map { stored =>
                  val text = stored.getOrElse(if kind == "head" then "" else PostingText.settingTxtDefault)
                  Responses.plain(if kind == "head" then text else text.replace("<DIR_NAME>", bbs), "plain", "Shift_JIS")
                }
              }
            case _ => Future.successful(notFound(path))
          }
      }
    }
    result.toJSPromise
  }
}
