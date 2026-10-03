package ch3s.bbs.handler

import ch3s.bbs.external.cloudflare.*
import ch3s.bbs.external.web.{Request, Response}
import ch3s.bbs.hash.WebHash
import ch3s.common.text.Utf8
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.concurrent.Future

object AdminHandler {
  private def str(v: js.Any): String = js.Dynamic.global.String(v).asInstanceOf[String]
  private def truth(v: js.Any): Boolean = js.Dynamic.global.Boolean(v).asInstanceOf[Boolean]
  private def blank(v: js.Any): String = if truth(v) then str(v) else ""
  private def escape(v: js.Any): String = (if v == null || js.isUndefined(v) then "" else str(v)).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
  private def fail(message: String): Nothing = throw js.JavaScriptException(new js.Error(message))
  private def board(v: js.Any): String = {
    if !new js.RegExp("^[a-zA-Z0-9_-]{1,80}$").test(blank(v)) then fail("板名が不正です。")
    str(v)
  }
  private def key(v: js.Any): String = {
    if !new js.RegExp("^\\d{10}$").test(blank(v)) then fail("keyは10桁の数字で指定してください。")
    str(v)
  }
  private def input(name: String, value: String, label: String): String = s"""<label>$label <input name="$name" value="${escape(value)}" required></label>"""
  private def area(name: String, value: String, label: String): String = s"""<label>$label<textarea name="$name" rows="16">${escape(value)}</textarea></label>"""
  private def form(body: String, action: String, label: String = "保存"): String = s"""<form method="post">$body<button name="action" value="$action">$label</button></form>"""
  private def text(e: WorkerEnv, file: String, fallback: String = ""): Future[String] = e.ch3www.get(file).toFuture.flatMap(o => if o == null then Future.successful(fallback) else o.text().toFuture)
  private def editable(v: String): String = {
    val entities = Map("&lt;" -> "<", "&gt;" -> ">", "&quot;" -> "\"", "&apos;" -> "'", "&amp;" -> "&")
    val replace: js.Function1[String, String] = s => entities(s)
    v.asInstanceOf[js.Dynamic].replace(new js.RegExp(" ?<br\\s*/?>(?: )?", "gi"), "\n").replace(new js.RegExp("&(?:lt|gt|quot|apos|amp);", "g"), replace).asInstanceOf[String]
  }

  def handle(r: Request, e: WorkerEnv): js.Promise[Response] = {
    val env = e.asInstanceOf[js.Dynamic]
    val headers = js.Dictionary("content-type" -> "text/html; charset=utf-8", "cache-control" -> "no-store", "x-content-type-options" -> "nosniff", "referrer-policy" -> "no-referrer", "content-security-policy" -> "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'")
    if !truth(env.ADMIN_PASSWORD) then return js.Promise.resolve(Responses.response("管理者パスワードが未設定です。", 503, headers))
    val username = if truth(env.ADMIN_USER) then str(env.ADMIN_USER) else "admin"
    val credentials = Utf8.encode(s"$username:${str(env.ADMIN_PASSWORD)}").map(_.toChar).mkString
    val expected = "Basic " + js.Dynamic.global.btoa(credentials).asInstanceOf[String]
    val a = WebHash.hash("SHA-256", expected).toFuture
    val b = WebHash.hash("SHA-256", Option(r.headers.get("authorization")).getOrElse("")).toFuture
    a.zip(b).flatMap { (left, right) =>
      if left != right then {
        headers("www-authenticate") = "Basic realm=\"3ch admin\", charset=\"UTF-8\""
        Future.successful(Responses.response("管理者認証が必要です。", 401, headers))
      } else if r.method != "GET" && r.method != "POST" then Future.successful(Responses.response("Method not allowed", 405, headers))
      else {
        val url = js.Dynamic.newInstance(js.Dynamic.global.URL)(r.url)
        val path = str(url.pathname)
        def page(content: String, status: Int = 200): Response = Responses.response(AdminText.page(content), status, headers)
        val action = Future.unit.flatMap { _ =>
          if r.method == "POST" then {
            val origin = r.headers.get("origin")
            if origin != str(url.origin) && !(origin == null && r.headers.get("sec-fetch-site") == "same-origin") then Future.successful(Responses.response("送信元が不正です。", 403, headers))
            else {
              if js.Dynamic.global.Number(r.headers.get("content-length")).asInstanceOf[Double] > 262144 then fail("入力が大きすぎます。")
              r.asInstanceOf[js.Dynamic].text().asInstanceOf[js.Promise[String]].toFuture.flatMap { body =>
                if Utf8.encode(body).length > 262144 then fail("入力が大きすぎます。")
                val data = js.Dynamic.global.Object.fromEntries(js.Dynamic.newInstance(js.Dynamic.global.URLSearchParams)(body))
                val save: Future[Unit] = path match {
                  case "/admin/board" =>
                    val bbs = board(url.searchParams.get("bbs"))
                    if str(data.action) != "save" then fail("操作が不正です。")
                    e.ch3www.put(s"$bbs/head.txt", blank(data.head)).toFuture.flatMap(_ => e.ch3www.put(s"$bbs/SETTING.TXT", blank(data.settings)).toFuture).map(_ => ())
                  case "/admin/access" | "/admin/ngwords" =>
                    Moderation.parseRules(blank(data.rules))
                    e.ch3www.put(if path.endsWith("access") then "deny.cgi" else "ngwords.cgi", blank(data.rules)).toFuture.map(_ => ())
                  case "/admin/menu" => e.ch3www.put("bbsmenu.html", blank(data.html)).toFuture.map(_ => ())
                  case "/admin/thread" =>
                    val bbs = board(url.searchParams.get("bbs")); val k = key(url.searchParams.get("key"))
                    if str(data.action) == "move" then { board(data.targetBoard); key(data.targetKey) }
                    val stub = e.THREAD_DO.get(e.THREAD_DO.idFromName(s"$bbs/$k")).asInstanceOf[ThreadDoStub]
                    stub.inspect(bbs, k).toFuture.flatMap(_ => stub.moderate(str(data.action), js.Dynamic.global.Object.assign(js.Dynamic.literal(), data, js.Dynamic.literal(bbs = bbs, key = k))).toFuture)
                  case _ => fail("操作先が不正です。")
                }
                save.map { _ => headers("location") = path + str(url.search); Responses.response(null, 303, headers) }
              }
            }
          } else render(path, url, e).map { case (html, status) => page(html, status) }
        }
        action.recover { case ex =>
          val message = ex match {
            case x: js.JavaScriptException => x.exception.asInstanceOf[js.Dynamic].message
            case _ => ex.getMessage.asInstanceOf[js.Any]
          }
          page(s"<h1>処理できませんでした</h1><p>${escape(message)}</p><p>ブラウザーで戻り、入力内容を確認してください。</p>", 400)
        }
      }
    }.toJSPromise
  }

  private def render(path: String, url: js.Dynamic, e: WorkerEnv): Future[(String, Int)] = {
    val html: Future[String] = path match {
      case "/admin" | "/admin/" => Future.successful("<h1>管理画面top</h1><p>板名を指定して管理対象を開いてください。</p><form action=\"/admin/board\">" + input("bbs", "", "板名") + "<button>板管理</button></form><form action=\"/admin/threads\">" + input("bbs", "", "板名") + "<button>スレ管理</button></form>")
      case "/admin/board" =>
        val bbs = board(url.searchParams.get("bbs"))
        for {
          head <- text(e, s"$bbs/head.txt")
          settings <- text(e, s"$bbs/SETTING.TXT", PostingText.settingTxtDefault.replace("<DIR_NAME>", bbs))
        } yield s"<h1>板管理: ${escape(bbs)}</h1>" + form(area("head", head, "head.txt") + area("settings", settings, "SETTING.TXT"), "save")
      case "/admin/threads" =>
        val bbs = board(url.searchParams.get("bbs"))
        val stub = e.BOARD_DO.get(e.BOARD_DO.idFromName(bbs)).asInstanceOf[BoardDoStub]
        for {
          subject <- stub.getSubject().toFuture
          removed <- stub.getRemoved().toFuture
        } yield {
          val out = new StringBuilder(s"""<h1>スレ管理: ${escape(bbs)}</h1><form action="/admin/thread">${input("bbs", bbs, "板名")}${input("key", "", "key")}<button>開く</button></form><h2>公開中</h2><ul>""")
          subject.split("\n", -1).filter(_.nonEmpty).foreach { line => out.append(s"""<li><a href="/admin/thread?bbs=$bbs&amp;key=${escape(line.split("\\.", -1)(0))}">${escape(line)}</a></li>""") }
          out.append("</ul><h2>削除・移動済み（ログ保持）</h2><ul>")
          removed.foreach(k => out.append(s"""<li><a href="/admin/thread?bbs=$bbs&amp;key=$k">$k</a></li>"""))
          out.append("</ul>").toString
        }
      case "/admin/thread" =>
        val bbs = board(url.searchParams.get("bbs")); val k = key(url.searchParams.get("key"))
        e.THREAD_DO.get(e.THREAD_DO.idFromName(s"$bbs/$k")).asInstanceOf[ThreadDoStub].inspect(bbs, k).toFuture.map { data =>
          val dat = data.dat.getOrElse("")
          if dat.isEmpty then fail("スレッドがありません。")
          val stopped = data.stopped.getOrElse(false)
          val state = if data.hidden.getOrElse(false) then "削除・移動済み" else if stopped then "停止中" else "公開中"
          val out = new StringBuilder(s"""<h1>スレ管理: $bbs/$k</h1><p><a href="/admin/threads?bbs=$bbs">一覧へ</a></p><p>状態: $state ${escape(data.movedTo.getOrElse(""))}</p>""")
          out.append(form("", if stopped then "resume" else "stop", if stopped then "スレ停止解除" else "スレ停止"))
          out.append("<details><summary>スレッド削除（本文・投稿者ログは保持）</summary>" + form("", "delete", "公開一覧とdatから削除") + "</details>")
          out.append("<details><summary>スレッド移動（板移動 / key変更）</summary>" + form(input("targetBoard", bbs, "移動先の板") + input("targetKey", k, "移動先key"), "move", "移動する") + "</details><h2>レス一覧・編集・削除</h2>")
          dat.asInstanceOf[js.Dynamic].trimEnd().asInstanceOf[String].split("\n", -1).zipWithIndex.foreach { (line, i) =>
            val f = line.split("<>", -1)
            def field(index: Int) = f.lift(index).getOrElse("")
            out.append(s"<details><summary>${i + 1}: ${escape(field(0))} ${escape(field(2))}</summary><pre>${escape(field(3))}</pre>")
            val hidden = s"""<input type="hidden" name="number" value="${i + 1}">"""
            out.append(form(hidden + input("name", editable(field(0)), "名前") + s"""<label>メール<input name="mail" value="${escape(editable(field(1)))}"></label>""" + area("message", editable(field(3)), "本文（テキスト入力）"), "editRes", "レスを編集"))
            out.append(form(hidden, "deleteRes", "レスをあぼーん化") + "</details>")
          }
          out.append(s"<h2>投稿者ログ（削除後も保持）</h2><pre>${escape(data.log.getOrElse(""))}</pre><h2>datログ</h2><pre>${escape(dat)}</pre>")
          data.legacyLog.filter(_.nonEmpty).foreach(log => out.append(s"<h2>旧形式ログ</h2><pre>${escape(log)}</pre>"))
          out.toString
        }
      case "/admin/access" | "/admin/ngwords" =>
        val access = path.endsWith("access")
        text(e, if access then "deny.cgi" else "ngwords.cgi").map(rules => AdminText.rules(access) + form(area("rules", rules, "規制ルール"), "save"))
      case "/admin/menu" => text(e, "bbsmenu.html").map(html => "<h1>板一覧（bbsmenu.html）編集</h1><p>公開するHTMLを入力してください。保存後はこの内容を使用します。</p>" + form(area("html", html, "bbsmenu.html"), "save"))
      case _ => return Future.successful(("<h1>ページがありません。</h1>", 404))
    }
    html.map(_ -> 200)
  }
}
