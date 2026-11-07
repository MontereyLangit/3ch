package io._3ch.bbs.handler

import io._3ch.bbs.application.admin.{AdminFailure, AdminFile, AdminService}
import io._3ch.bbs.application.admin.ports.WriteCondition
import io._3ch.bbs.external.cloudflare.*
import io._3ch.bbs.external.web.{Request, Response, TextEncoder}
import io._3ch.bbs.hash.WebHash
import io._3ch.bbs.domain.posting.moderation.ThreadCreationSettings
import io._3ch.bbs.repository.AdminRepository
import io._3ch.common.admin.{RuleName, ThreadAction}
import io._3ch.common.text.JsNumber
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.concurrent.Future

/**
  * 管理画面用ハンドラ
  */
object AdminHandler {
  private def str(v: js.Any): String = js.Dynamic.global.String(v).asInstanceOf[String]
  private def truth(v: js.Any): Boolean = js.Dynamic.global.Boolean(v).asInstanceOf[Boolean]
  private def blank(v: js.Any): String = if truth(v) then str(v) else ""
  private def escape(v: js.Any): String = (if v == null || js.isUndefined(v) then "" else str(v)).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
  private def fail(message: String): Nothing = throw js.JavaScriptException(new js.Error(message))
  private def ok[A](result: Either[AdminFailure, A]): A = result.fold(failure => fail(AdminText.failure(failure)), identity)
  private def board(v: js.Any): String = ok(AdminService.validBoard(blank(v)))
  private def key(v: js.Any): String = ok(AdminService.validKey(blank(v)))
  private def service(e: WorkerEnv): AdminService = new AdminService(AdminRepository.files(e), AdminRepository.threads(e))
  private def input(name: String, value: String, label: String): String = s"""<label>$label <input name="$name" value="${escape(value)}" required></label>"""
  private def area(name: String, value: String, label: String): String = s"""<label>$label<textarea name="$name" rows="16">${escape(value)}</textarea></label>"""
  private def form(body: String, action: String, label: String = "保存"): String = s"""<form method="post">$body<button name="action" value="$action">$label</button></form>"""
  /** 読込時の ETag。ファイルが無ければ空 */
  private def etagInput(name: String, etag: Option[String]): String = s"""<input type="hidden" name="$name" value="${escape(etag.getOrElse(""))}">"""
  /** フォームの ETag による条件。項目が無い（従来の画面からの送信）なら条件なし、空ならファイルが無いときだけ */
  private def condition(data: js.Dynamic, name: String): WriteCondition =
    if js.isUndefined(data.selectDynamic(name)) then WriteCondition.Always else AdminService.condition(Option(blank(data.selectDynamic(name))).filter(_.nonEmpty))
  private val creationSettingFields = List(
    "BBS_THREAD_TATESUGI" -> "通常規制の件数",
    "BBS_THREAD_TATESUGI_RESET" -> "通常規制の有効期間（秒）",
    "BBS_THREAD_JUNBAN" -> "広域規制の件数",
    "BBS_THREAD_JUNBAN_RESET" -> "広域規制の有効期間（秒）",
    "BBS_THREAD_JUNBAN_AS_ONLY" -> "県で分けずAS全体を対象にするASN（空白・カンマ区切り）"
  )
  private def creationForm(settings: String): String = {
    val parsed = ThreadCreationSettings.parseText(settings)
    val values = parsed.toOption.map { p => Map(
      "BBS_THREAD_TATESUGI" -> p.limits.threadTatesugi.toString,
      "BBS_THREAD_TATESUGI_RESET" -> p.limits.threadTatesugiResetSeconds.toLong.toString,
      "BBS_THREAD_JUNBAN" -> p.limits.threadJunban.toString,
      "BBS_THREAD_JUNBAN_RESET" -> p.limits.threadJunbanResetSeconds.toLong.toString,
      "BBS_THREAD_JUNBAN_AS_ONLY" -> p.asOnly.toList.sorted.mkString(" ")
    ) }.getOrElse(ThreadCreationSettings.settingsMap(settings))
    val fields = creationSettingFields.map { (name, label) =>
      s"""<label>$label <input name="$name" value="${escape(values.getOrElse(name, ""))}"></label>"""
    }.mkString
    "<h2>スレ立て規制</h2><p>通常は同一IP・スレタイ、広域は同一UA・AS＋県（海外は海外全体）。件数0でその規制を無効にします。</p>" +
      parsed.left.toOption.map(reason => s"<p>${escape(PostingText.creationSettingsMessage(reason))}</p>").getOrElse("") + form(fields, "saveCreationLimit", "スレ立て規制を保存")
  }
  /** フォームのレス番号。JavaScript の Number で整数として読めなければ 0（Thread DO が拒否する） */
  private def resNumber(v: js.Any): Int = {
    val number = JsNumber.parse(blank(v))
    if number == math.rint(number) && number >= 1 && number <= Int.MaxValue then number.toInt else 0
  }
  private def threadAction(data: js.Dynamic): Option[ThreadAction] = str(data.action) match {
    case "stop" => Some(ThreadAction.Stop)
    case "resume" => Some(ThreadAction.Resume)
    case "delete" => Some(ThreadAction.Delete)
    case "move" => Some(ThreadAction.Move(blank(data.targetBoard), blank(data.targetKey)))
    case "editRes" => Some(ThreadAction.EditRes(resNumber(data.number), blank(data.name), blank(data.mail), blank(data.message)))
    case "deleteRes" => Some(ThreadAction.DeleteRes(resNumber(data.number)))
    case _ => None
  }

  def handle(r: Request, e: WorkerEnv): js.Promise[Response] = {
    val env = e.asInstanceOf[js.Dynamic]
    val headers = js.Dictionary("content-type" -> "text/html; charset=utf-8", "cache-control" -> "no-store", "x-content-type-options" -> "nosniff", "referrer-policy" -> "no-referrer", "content-security-policy" -> "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'")
    if !truth(env.ADMIN_PASSWORD) then return js.Promise.resolve(Responses.response("管理者パスワードが未設定です。", 503, headers))
    val username = if truth(env.ADMIN_USER) then str(env.ADMIN_USER) else "admin"
    val credentialBytes = new TextEncoder().encode(s"$username:${str(env.ADMIN_PASSWORD)}")
    val credentials = (0 until credentialBytes.length).map(i => credentialBytes(i).toChar).mkString
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
        val path = str(url.pathname).stripSuffix("/")
        def page(content: String, status: Int = 200): Response = Responses.response(AdminText.page(content), status, headers)
        val action = Future.unit.flatMap { _ =>
          if r.method == "POST" then {
            val origin = r.headers.get("origin")
            if js.Dynamic.global.Number(r.headers.get("content-length")).asInstanceOf[Double] > 262144 then fail("入力が大きすぎます。")
            r.asInstanceOf[js.Dynamic].text().asInstanceOf[js.Promise[String]].toFuture.flatMap { body =>
              if new TextEncoder().encode(body).length > 262144 then fail("入力が大きすぎます。")
              val data = js.Dynamic.global.Object.fromEntries(js.Dynamic.newInstance(js.Dynamic.global.URLSearchParams)(body))
              val admin = service(e)
              val save: Future[Unit] = path match {
                case "/admin/board" =>
                  val bbs = board(url.searchParams.get("bbs"))
                  str(data.action) match {
                    case "save" =>
                      admin.saveBoardTexts(bbs, blank(data.head), blank(data.settings), condition(data, "settingsEtag"), condition(data, "headEtag")).map(ok).map { saved =>
                        if saved.partial then fail(AdminText.failure(AdminFailure.Conflict(AdminFile.Head)))
                      }
                    case "saveCreationLimit" => admin.saveCreationLimits(bbs, creationSettingFields.map((name, _) => name -> blank(data.selectDynamic(name)))).map(ok)
                    case _ => fail("操作が不正です。")
                  }
                case "/admin/access" | "/admin/ngwords" =>
                  admin.saveRules(if path.endsWith("access") then RuleName.Access else RuleName.Ngwords, blank(data.rules), condition(data, "etag")).map(ok).map(_ => ())
                case "/admin/menu" => admin.saveMenu(blank(data.html), condition(data, "etag")).map(ok).map(_ => ())
                case "/admin/thread" =>
                  val bbs = board(url.searchParams.get("bbs")); val k = key(url.searchParams.get("key"))
                  threadAction(data).fold(fail("操作が不正です。"))(action => admin.moderate(bbs, k, action).map(ok))
                case _ => fail("操作先が不正です。")
              }
              save.map { _ => headers("location") = path + str(url.search); Responses.response(null, 303, headers) }
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
    val admin = service(e)
    val html: Future[String] = path match {
      case "/admin" | "/admin/" => Future.successful("<h1>管理画面top</h1><p>板名を指定して管理対象を開いてください。</p><form action=\"/admin/board\">" + input("bbs", "", "板名") + "<button>板管理</button></form><form action=\"/admin/threads\">" + input("bbs", "", "板名") + "<button>スレ管理</button></form>")
      case "/admin/board" =>
        val bbs = board(url.searchParams.get("bbs"))
        admin.boardTexts(bbs).map { (head, settings) =>
          val etags = etagInput("settingsEtag", settings.etag) + etagInput("headEtag", head.etag)
          s"<h1>板管理: ${escape(bbs)}</h1>" + creationForm(settings.text) + form(etags + area("head", head.text, "head.txt") + area("settings", settings.text, "SETTING.TXT"), "save")
        }
      case "/admin/threads" =>
        val bbs = board(url.searchParams.get("bbs"))
        admin.threadList(bbs).map { list =>
          val out = new StringBuilder(s"""<h1>スレ管理: ${escape(bbs)}</h1><form action="/admin/thread">${input("bbs", bbs, "板名")}${input("key", "", "key")}<button>開く</button></form><h2>公開中</h2><ul>""")
          list.subject.foreach { thread => out.append(s"""<li><a href="/admin/thread?bbs=$bbs&amp;key=${escape(thread.key)}">${escape(thread.line)}</a></li>""") }
          out.append("</ul><h2>削除・移動済み（ログ保持）</h2><ul>")
          list.removed.foreach(k => out.append(s"""<li><a href="/admin/thread?bbs=$bbs&amp;key=$k">$k</a></li>"""))
          out.append("</ul>").toString
        }
      case "/admin/thread" =>
        val bbs = board(url.searchParams.get("bbs")); val k = key(url.searchParams.get("key"))
        admin.thread(bbs, k).map(ok).map { data =>
          val stopped = data.stopped
          val state = if data.hidden then "削除・移動済み" else if stopped then "停止中" else "公開中"
          val out = new StringBuilder(s"""<h1>スレ管理: $bbs/$k</h1><p><a href="/admin/threads?bbs=$bbs">一覧へ</a></p><p>状態: $state ${escape(data.movedTo.getOrElse(""))}</p>""")
          out.append(form("", if stopped then "resume" else "stop", if stopped then "スレ停止解除" else "スレ停止"))
          out.append("<details><summary>スレッド削除（本文・投稿者ログは保持）</summary>" + form("", "delete", "公開一覧とdatから削除") + "</details>")
          out.append("<details><summary>スレッド移動（板移動 / key変更）</summary>" + form(input("targetBoard", bbs, "移動先の板") + input("targetKey", k, "移動先key"), "move", "移動する") + "</details><h2>レス一覧・編集・削除</h2>")
          data.res.foreach { res =>
            out.append(s"<details><summary>${res.number}: ${escape(res.name)} ${escape(res.date)}</summary><pre>${escape(res.message)}</pre>")
            val hidden = s"""<input type="hidden" name="number" value="${res.number}">"""
            out.append(form(hidden + input("name", res.edit.name, "名前") + s"""<label>メール<input name="mail" value="${escape(res.edit.mail)}"></label>""" + area("message", res.edit.message, "本文（テキスト入力）"), "editRes", "レスを編集"))
            out.append(form(hidden, "deleteRes", "レスをあぼーん化") + "</details>")
          }
          out.append(s"<h2>投稿者ログ（削除後も保持）</h2><pre>${escape(data.log)}</pre><h2>datログ</h2><pre>${escape(data.dat)}</pre>")
          data.legacyLog.filter(_.nonEmpty).foreach(log => out.append(s"<h2>旧形式ログ</h2><pre>${escape(log)}</pre>"))
          out.toString
        }
      case "/admin/access" | "/admin/ngwords" =>
        val access = path.endsWith("access")
        admin.rules(if access then RuleName.Access else RuleName.Ngwords).map(rules => AdminText.rules(access) + form(etagInput("etag", rules.etag) + area("rules", rules.text, "規制ルール"), "save"))
      case "/admin/menu" => admin.menu().map(menu => "<h1>板一覧（bbsmenu.html）編集</h1><p>公開するHTMLを入力してください。保存後はこの内容を使用します。</p>" + form(etagInput("etag", menu.etag) + area("html", menu.text, "bbsmenu.html"), "save"))
      case _ => return Future.successful(("<h1>ページがありません。</h1>", 404))
    }
    html.map(_ -> 200)
  }
}
