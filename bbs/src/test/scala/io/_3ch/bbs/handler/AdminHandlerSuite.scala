package io._3ch.bbs.handler

import munit.FunSuite
import io._3ch.bbs.external.cloudflare.WorkerEnv
import scala.collection.mutable
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

/** 管理画面の認証と、Origin / Fetch Metadata によらず認証済み変更を受け付ける仕様を検証する。 */
class AdminHandlerSuite extends FunSuite {
  import HandlerTestKit.*

  private def admin(req: io._3ch.bbs.external.web.Request, env: js.Dynamic): Future[js.Dynamic] =
    fut[js.Dynamic](AdminHandler.handle(req, env.asInstanceOf[WorkerEnv]))

  private def basic(credentials: String): String =
    "Basic " + js.Dynamic.global.Buffer.from(credentials).applyDynamic("toString")("base64").asInstanceOf[String]

  test("スレ立て規制の管理フォームは既存設定を保持し、不正なASNを保存しない") {
    val env = io._3ch.bbs.durable.DurableTestKit.newEnv()
    val raw = env.worker.asInstanceOf[js.Dynamic]
    raw.ADMIN_PASSWORD = "test"
    env.putR2("sample/SETTING.TXT", "BBS_TITLE=残す板名\nBBS_THREAD_TATESUGI=3/86400\n")
    env.putR2("sample/head.txt", "残す案内")
    val url = "https://example.test/admin/board?bbs=sample"
    val auth = Seq("authorization" -> basic("admin:test"))
    def save(asn: String): Future[js.Dynamic] = {
      val fields = Seq("action" -> "saveCreationLimit", "BBS_THREAD_TATESUGI" -> "5", "BBS_THREAD_TATESUGI_RESET" -> "120",
        "BBS_THREAD_JUNBAN" -> "2", "BBS_THREAD_JUNBAN_RESET" -> "60", "BBS_THREAD_JUNBAN_AS_ONLY" -> asn)
      val body = fields.map((k, v) => s"$k=${js.URIUtils.encodeURIComponent(v)}").mkString("&")
      admin(request(url, auth, method = "POST", body = body), raw)
    }
    for {
      page <- admin(request(url, auth), raw)
      html <- text(page)
      _ = assert(html.contains("saveCreationLimit"))
      _ = assert(html.contains("value=\"86400\""))
      saved <- save("AS2516, 4713")
      _ = assertEquals(status(saved), 303)
      settings = env.r2Text("sample/SETTING.TXT").get
      _ = assert(settings.contains("BBS_TITLE=残す板名"))
      _ = assert(settings.contains("BBS_THREAD_TATESUGI=5\n"))
      _ = assertEquals(env.r2Text("sample/head.txt"), Some("残す案内"))
      bad <- save("invalid-AS")
      _ = assertEquals(status(bad), 400)
      _ = assertEquals(env.r2Text("sample/SETTING.TXT"), Some(settings))
      unauthorized <- admin(request(url, method = "POST", body = "action=saveCreationLimit"), raw)
      _ = assertEquals(status(unauthorized), 401)
      _ = assertEquals(env.r2Text("sample/SETTING.TXT"), Some(settings))
    } yield ()
  }

  test("規制ルールの書式の誤りは行番号付きで示し、保存しない") {
    val env = io._3ch.bbs.durable.DurableTestKit.newEnv()
    val raw = env.worker.asInstanceOf[js.Dynamic]
    raw.ADMIN_PASSWORD = "test"
    env.putR2("deny.cgi", "<><><>deny")
    def save(path: String, rules: String): Future[(Int, String)] = {
      val req = request(s"https://example.test$path", Seq("authorization" -> basic("admin:test")), method = "POST", body = s"rules=${js.URIUtils.encodeURIComponent(rules)}")
      admin(req, raw).flatMap(res => text(res).map(status(res) -> _))
    }
    for {
      format <- save("/admin/access", "# ok\nbad")
      action <- save("/admin/access", "<><><>unknown")
      pattern <- save("/admin/ngwords", "<><>[<>deny")
    } yield {
      assertEquals(format._1, 400)
      assert(format._2.contains("2行目: 板&lt;&gt;対象&lt;&gt;NGワード&lt;&gt;動作 の形式で入力してください。"), format._2)
      assert(action._2.contains("1行目: 動作が不正です。"), action._2)
      assert(pattern._2.contains("1行目: 正規表現が不正です。"), pattern._2)
      assertEquals(env.r2Text("deny.cgi"), Some("<><><>deny"))
      assertEquals(env.r2Text("ngwords.cgi"), None)
    }
  }

  test("規制ルールの 5 列目の回避レベルを検証し、正しい値と 4 列の行は保存する") {
    val env = io._3ch.bbs.durable.DurableTestKit.newEnv()
    val raw = env.worker.asInstanceOf[js.Dynamic]
    raw.ADMIN_PASSWORD = "test"
    def save(path: String, rules: String): Future[(Int, String)] = {
      val req = request(s"https://example.test$path", Seq("authorization" -> basic("admin:test")), method = "POST", body = s"rules=${js.URIUtils.encodeURIComponent(rules)}")
      admin(req, raw).flatMap(res => text(res).map(status(res) -> _))
    }
    val accepted = "<><><>deny\n<>AS1-<><>deny<>staff\n<><>語<>mark<>admin\n<><><>TateDame<>"
    for {
      invalid <- save("/admin/access", "<><><>deny\n<><><>deny<>everyone")
      _ = assertEquals(invalid._1, 400)
      _ = assert(invalid._2.contains("2行目: 回避レベルは admin・staff・privileged・l10・login のいずれかにしてください。"), invalid._2)
      _ = assertEquals(env.r2Text("deny.cgi"), None)
      saved <- save("/admin/ngwords", accepted)
    } yield {
      assertEquals(saved._1, 303)
      assertEquals(env.r2Text("ngwords.cgi"), Some(accepted))
    }
  }

  test("スレ立て規制の専用フォームは読込後に他操作で更新されたSETTING.TXTを上書きしない") {
    val env = io._3ch.bbs.durable.DurableTestKit.newEnv()
    val raw = env.worker.asInstanceOf[js.Dynamic]
    raw.ADMIN_PASSWORD = "test"
    env.putR2("sample/SETTING.TXT", "BBS_TITLE=旧板名\n")
    val concurrent = "BBS_TITLE=新板名\n"
    var injected = false
    env.onR2Get { key =>
      if (key == "sample/SETTING.TXT" && !injected) { injected = true; env.putR2(key, concurrent) }
    }
    val body = "action=saveCreationLimit&BBS_THREAD_TATESUGI=5&BBS_THREAD_TATESUGI_RESET=120&BBS_THREAD_JUNBAN=2&BBS_THREAD_JUNBAN_RESET=60&BBS_THREAD_JUNBAN_AS_ONLY="
    for {
      res <- admin(request("https://example.test/admin/board?bbs=sample", Seq("authorization" -> basic("admin:test")), method = "POST", body = body), raw)
      html <- text(res)
    } yield {
      assertEquals(status(res), 400)
      assert(html.contains("他の操作で板設定が更新されました。"), html)
      assertEquals(env.r2Text("sample/SETTING.TXT"), Some(concurrent))
    }
  }

  test("スレ立て規制の専用フォームは各入力のCR・LFを拒否し板設定と案内を保持する") {
    val env = io._3ch.bbs.durable.DurableTestKit.newEnv()
    val raw = env.worker.asInstanceOf[js.Dynamic]
    raw.ADMIN_PASSWORD = "test"
    val settings = "BBS_TITLE=残す板名\nBBS_THREAD_TATESUGI=3/86400\n"
    env.putR2("sample/SETTING.TXT", settings)
    env.putR2("sample/head.txt", "残す案内")
    val fields = Seq("BBS_THREAD_TATESUGI" -> "5", "BBS_THREAD_TATESUGI_RESET" -> "120",
      "BBS_THREAD_JUNBAN" -> "2", "BBS_THREAD_JUNBAN_RESET" -> "60", "BBS_THREAD_JUNBAN_AS_ONLY" -> "2516")
    val cases = for ((name, _) <- fields; separator <- Seq("\r", "\n", "\r\n")) yield (name, separator)
    cases.foldLeft(Future.unit) { case (previous, (target, separator)) =>
      previous.flatMap { _ =>
        val body = (("action" -> "saveCreationLimit") +: fields.map { (name, value) =>
          name -> (if (name == target) value + separator + "BBS_TITLE=書換" else value)
        }).map((name, value) => s"$name=${js.URIUtils.encodeURIComponent(value)}").mkString("&")
        admin(request("https://example.test/admin/board?bbs=sample", Seq("authorization" -> basic("admin:test")), method = "POST", body = body), raw).map { result =>
          assertEquals(status(result), 400, s"$target: ${js.JSON.stringify(separator)}")
          assertEquals(env.r2Text("sample/SETTING.TXT"), Some(settings))
          assertEquals(env.r2Text("sample/head.txt"), Some("残す案内"))
        }
      }
    }
  }

  test("板設定の通常保存も規制設定が不正ならhead.txtを書き換える前に拒否する") {
    val env = io._3ch.bbs.durable.DurableTestKit.newEnv()
    val raw = env.worker.asInstanceOf[js.Dynamic]
    raw.ADMIN_PASSWORD = "test"
    val settings = "BBS_TITLE=残す板名\n"
    env.putR2("sample/SETTING.TXT", settings)
    env.putR2("sample/head.txt", "残す案内")
    val body = Seq("action" -> "save", "head" -> "書換後の案内", "settings" -> "BBS_THREAD_TATESUGI=invalid\n")
      .map((name, value) => s"$name=${js.URIUtils.encodeURIComponent(value)}").mkString("&")
    admin(request("https://example.test/admin/board?bbs=sample", Seq("authorization" -> basic("admin:test")), method = "POST", body = body), raw).map { result =>
      assertEquals(status(result), 400)
      assertEquals(env.r2Text("sample/SETTING.TXT"), Some(settings))
      assertEquals(env.r2Text("sample/head.txt"), Some("残す案内"))
    }
  }

  test("管理認証は未設定・誤認証を拒否し、UTF-8資格情報を受け付ける") {
    val env = js.Dynamic.literal(ADMIN_USER = "管理者", ADMIN_PASSWORD = "テスト用パスワード")
    val authorization = basic("管理者:テスト用パスワード")
    for {
      unset <- admin(request("https://example.test/admin/"), js.Dynamic.literal())
      _ = assertEquals(status(unset), 503)
      unauthorized <- admin(request("https://example.test/admin/"), env)
      _ = assertEquals(status(unauthorized), 401)
      ok <- admin(request("https://example.test/admin/", Seq("authorization" -> authorization)), env)
      _ = assertEquals(status(ok), 200)
      _ = assertEquals(header(ok, "cache-control"), Some("no-store"))
      _ = assert(header(ok, "content-security-policy").exists(_.contains("frame-ancestors 'none'")))
      deleted <- admin(request("https://example.test/admin/", Seq("authorization" -> authorization), method = "DELETE"), env)
      _ = assertEquals(status(deleted), 405)
    } yield ()
  }

  test("認証済みの管理変更はOriginとFetch Metadataによらず受け付ける") {
    val writes = mutable.ListBuffer.empty[String]
    val env = js.Dynamic.literal(
      ADMIN_PASSWORD = "test",
      ch3www = js.Dynamic.literal(
        put = ((key: String) => { writes += key; js.Promise.resolve[js.Any](js.undefined) }): js.Function1[String, js.Promise[js.Any]]
      )
    )
    val authorization = basic("admin:test")
    def post(headers: (String, String)*): Future[Int] =
      admin(request("https://example.test/admin/menu", Seq("authorization" -> authorization) ++ headers, method = "POST", body = "html=menu"), env).map(status)
    for {
      noOrigin <- post()
      _ = assertEquals(noOrigin, 303)
      crossOrigin <- post("origin" -> "https://other.test")
      _ = assertEquals(crossOrigin, 303)
      nullOrigin <- post("origin" -> "null", "sec-fetch-site" -> "same-origin")
      _ = assertEquals(nullOrigin, 303)
      sameSite <- post("sec-fetch-site" -> "same-site")
      _ = assertEquals(sameSite, 303)
      _ = assertEquals(writes.length, 4)
      fetchMetadata <- post("sec-fetch-site" -> "same-origin")
      _ = assertEquals(fetchMetadata, 303)
      origin <- post("origin" -> "https://example.test")
      _ = assertEquals(origin, 303)
      _ = assertEquals(writes.toList, List.fill(6)("bbsmenu.html"))
    } yield ()
  }

  test("板設定は読込時の ETag で SETTING.TXT・head.txt の順に条件付きで保存する") {
    val env = io._3ch.bbs.durable.DurableTestKit.newEnv()
    val raw = env.worker.asInstanceOf[js.Dynamic]
    raw.ADMIN_PASSWORD = "test"
    env.putR2Raw("sample/SETTING.TXT", "BBS_TITLE=旧\n", "s1")
    env.putR2Raw("sample/head.txt", "旧案内", "h1")
    val url = "https://example.test/admin/board?bbs=sample"
    val auth = Seq("authorization" -> basic("admin:test"))
    def save(settingsEtag: String, headEtag: String, title: String): Future[(Int, String)] = {
      val body = Seq("action" -> "save", "settingsEtag" -> settingsEtag, "headEtag" -> headEtag, "head" -> s"$title の案内", "settings" -> s"BBS_TITLE=$title\n")
        .map((name, value) => s"$name=${js.URIUtils.encodeURIComponent(value)}").mkString("&")
      admin(request(url, auth, method = "POST", body = body), raw).flatMap(res => text(res).map(status(res) -> _))
    }
    for {
      page <- admin(request(url, auth), raw).flatMap(text)
      _ = assert(page.contains("""name="settingsEtag" value="s1""""), page)
      _ = assert(page.contains("""name="headEtag" value="h1""""), page)
      stale <- save("old", "h1", "競合")
      _ = assertEquals(stale._1, 400)
      _ = assert(stale._2.contains("他の操作で板設定が更新されました。"), stale._2)
      _ = assertEquals(env.r2Text("sample/SETTING.TXT"), Some("BBS_TITLE=旧\n"))
      _ = assertEquals(env.r2Text("sample/head.txt"), Some("旧案内"))
      partial <- save("s1", "old", "一部")
      _ = assertEquals(partial._1, 400)
      _ = assert(partial._2.contains("SETTING.TXTは保存しましたが、head.txtは他の操作で更新されていたため保存していません。"), partial._2)
      _ = assertEquals(env.r2Text("sample/SETTING.TXT"), Some("BBS_TITLE=一部\n"))
      _ = assertEquals(env.r2Text("sample/head.txt"), Some("旧案内"))
      created <- save("", "", "作成")
      _ = assertEquals(created._1, 400, "読込時に無かったファイルが作られていれば上書きしない")
      fresh <- admin(request(url, auth), raw).flatMap(text)
      etag = (name: String) => s"""name="$name" value="([^"]*)"""".r.findFirstMatchIn(fresh).get.group(1)
      saved <- save(etag("settingsEtag"), etag("headEtag"), "成功")
    } yield {
      assertEquals(saved._1, 303)
      assertEquals(env.r2Text("sample/SETTING.TXT"), Some("BBS_TITLE=成功\n"))
      assertEquals(env.r2Text("sample/head.txt"), Some("成功 の案内"))
    }
  }

  test("未作成の板設定は空の ETag でだけ作成し、ETag の無い従来の送信は条件なしで保存する") {
    val env = io._3ch.bbs.durable.DurableTestKit.newEnv()
    val raw = env.worker.asInstanceOf[js.Dynamic]
    raw.ADMIN_PASSWORD = "test"
    val url = "https://example.test/admin/board?bbs=fresh"
    val auth = Seq("authorization" -> basic("admin:test"))
    def post(fields: (String, String)*): Future[Int] =
      admin(request(url, auth, method = "POST", body = fields.map((name, value) => s"$name=${js.URIUtils.encodeURIComponent(value)}").mkString("&")), raw).map(status)
    for {
      page <- admin(request(url, auth), raw).flatMap(text)
      _ = assert(page.contains("""name="settingsEtag" value="""""), page)
      created <- post("action" -> "save", "settingsEtag" -> "", "headEtag" -> "", "head" -> "案内", "settings" -> "BBS_TITLE=新\n")
      _ = assertEquals(created, 303)
      legacy <- post("action" -> "save", "head" -> "従来", "settings" -> "BBS_TITLE=従来\n")
    } yield {
      assertEquals(legacy, 303)
      assertEquals(env.r2Text("fresh/SETTING.TXT"), Some("BBS_TITLE=従来\n"))
      assertEquals(env.r2Text("fresh/head.txt"), Some("従来"))
    }
  }

  test("規制ルールと板一覧は読込時の ETag と違えば保存しない") {
    val env = io._3ch.bbs.durable.DurableTestKit.newEnv()
    val raw = env.worker.asInstanceOf[js.Dynamic]
    raw.ADMIN_PASSWORD = "test"
    env.putR2Raw("deny.cgi", "<><><>deny", "d1")
    env.putR2Raw("bbsmenu.html", "menu", "m1")
    val auth = Seq("authorization" -> basic("admin:test"))
    def post(path: String, fields: (String, String)*): Future[(Int, String)] =
      admin(request(s"https://example.test$path", auth, method = "POST", body = fields.map((name, value) => s"$name=${js.URIUtils.encodeURIComponent(value)}").mkString("&")), raw)
        .flatMap(res => text(res).map(status(res) -> _))
    for {
      access <- admin(request("https://example.test/admin/access", auth), raw).flatMap(text)
      _ = assert(access.contains("""name="etag" value="d1""""), access)
      menu <- admin(request("https://example.test/admin/menu", auth), raw).flatMap(text)
      _ = assert(menu.contains("""name="etag" value="m1""""), menu)
      staleRules <- post("/admin/access", "etag" -> "old", "rules" -> "")
      _ = assertEquals(staleRules._1, 400)
      _ = assert(staleRules._2.contains("他の操作で規制ルールが更新されました。"), staleRules._2)
      staleMenu <- post("/admin/menu", "etag" -> "old", "html" -> "")
      _ = assertEquals(staleMenu._1, 400)
      _ = assert(staleMenu._2.contains("他の操作で板一覧が更新されました。"), staleMenu._2)
      absent <- post("/admin/ngwords", "etag" -> "", "rules" -> "<><>語<>deny")
      _ = assertEquals(absent._1, 303)
      createdTwice <- post("/admin/ngwords", "etag" -> "", "rules" -> "")
      _ = assertEquals(createdTwice._1, 400)
      savedRules <- post("/admin/access", "etag" -> "d1", "rules" -> "")
      savedMenu <- post("/admin/menu", "etag" -> "m1", "html" -> "new")
    } yield {
      assertEquals(savedRules._1, 303)
      assertEquals(savedMenu._1, 303)
      assertEquals(env.r2Text("deny.cgi"), Some(""))
      assertEquals(env.r2Text("ngwords.cgi"), Some("<><>語<>deny"))
      assertEquals(env.r2Text("bbsmenu.html"), Some("new"))
    }
  }

  test("スレ立て規制の専用フォームは未作成の板設定が読込後に作られていれば上書きしない") {
    val env = io._3ch.bbs.durable.DurableTestKit.newEnv()
    val raw = env.worker.asInstanceOf[js.Dynamic]
    raw.ADMIN_PASSWORD = "test"
    var injected = false
    val original = raw.ch3www.get
    raw.ch3www.updateDynamic("get")(((key: String) => {
      val result = original.call(raw.ch3www, key)
      if (key == "sample/SETTING.TXT" && !injected) { injected = true; env.putR2(key, "BBS_TITLE=先に作成\n") }
      result
    }): js.Function1[String, js.Any])
    val body = "action=saveCreationLimit&BBS_THREAD_TATESUGI=5&BBS_THREAD_TATESUGI_RESET=120&BBS_THREAD_JUNBAN=2&BBS_THREAD_JUNBAN_RESET=60&BBS_THREAD_JUNBAN_AS_ONLY="
    admin(request("https://example.test/admin/board?bbs=sample", Seq("authorization" -> basic("admin:test")), method = "POST", body = body), raw).map { res =>
      assertEquals(status(res), 400)
      assertEquals(env.r2Text("sample/SETTING.TXT"), Some("BBS_TITLE=先に作成\n"))
    }
  }

  test("末尾スラッシュ付きパスは付かないパスと同じ画面・処理になる") {
    val kit = io._3ch.bbs.durable.DurableTestKit.newEnv()
    val env = kit.worker.asInstanceOf[js.Dynamic]
    env.updateDynamic("ADMIN_PASSWORD")("test")
    val authorization = basic("admin:test")
    def get(path: String): Future[(Int, String)] =
      admin(request("https://example.test" + path, Seq("authorization" -> authorization)), env).flatMap(res => text(res).map(status(res) -> _))
    def check(plain: String, slashed: String): Future[Unit] =
      for {
        a <- get(plain)
        b <- get(slashed)
      } yield {
        assertEquals(a._1, 200, plain)
        assertEquals(b, a, slashed)
      }
    for {
      _ <- check("/admin", "/admin/")
      _ <- check("/admin/menu", "/admin/menu/")
      _ <- check("/admin/access", "/admin/access/")
      _ <- check("/admin/ngwords", "/admin/ngwords/")
      _ <- check("/admin/board?bbs=sample", "/admin/board/?bbs=sample")
      post <- admin(request("https://example.test/admin/menu/?x=1", Seq("authorization" -> authorization), method = "POST", body = "html=slash"), env)
      _ = assertEquals(status(post), 303)
      _ = assertEquals(header(post, "location"), Some("/admin/menu?x=1"))
      _ = assertEquals(kit.r2Text("bbsmenu.html"), Some("slash"))
    } yield ()
  }
}
