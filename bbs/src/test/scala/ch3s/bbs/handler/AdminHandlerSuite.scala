package ch3s.bbs.handler

import munit.FunSuite
import ch3s.bbs.external.cloudflare.WorkerEnv
import scala.collection.mutable
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

/** 管理画面の認証と、Origin / Fetch Metadata によらず認証済み変更を受け付ける仕様を検証する。 */
class AdminHandlerSuite extends FunSuite {
  import HandlerTestKit.*

  private def admin(req: ch3s.bbs.external.web.Request, env: js.Dynamic): Future[js.Dynamic] =
    fut[js.Dynamic](AdminHandler.handle(req, env.asInstanceOf[WorkerEnv]))

  private def basic(credentials: String): String =
    "Basic " + js.Dynamic.global.Buffer.from(credentials).applyDynamic("toString")("base64").asInstanceOf[String]

  test("スレ立て規制の管理フォームは既存設定を保持し、不正なASNを保存しない") {
    val env = ch3s.bbs.durable.DurableTestKit.newEnv()
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

  test("スレ立て規制の専用フォームは読込後に他操作で更新されたSETTING.TXTを上書きしない") {
    val env = ch3s.bbs.durable.DurableTestKit.newEnv()
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
    val env = ch3s.bbs.durable.DurableTestKit.newEnv()
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
    val env = ch3s.bbs.durable.DurableTestKit.newEnv()
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
}
