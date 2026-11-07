package io._3ch.bbs.handler.admin

import io._3ch.bbs.durable.DurableTestKit
import io._3ch.bbs.external.cloudflare.WorkerEnv
import io._3ch.bbs.handler.AdminHandler
import io._3ch.bbs.handler.HandlerTestKit.*
import io._3ch.common.admin.{AdminCodec, BoardSettingsUpdate, Json, SettingValue}
import munit.FunSuite

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

/** 管理 API の正常系・検証・ETag 競合・入力上限を Basic 認証のまま確かめる */
class AdminApiSuite extends FunSuite {
  private val base = "https://example.test/admin/api"
  private val auth = "authorization" -> ("Basic " + js.Dynamic.global.Buffer.from("admin:test").applyDynamic("toString")("base64").asInstanceOf[String])
  /** 管理画面からの変更に付くヘッダ */
  private val fromAdmin = Seq("origin" -> "https://example.test", "x-ch3s-admin" -> "1")

  private final case class Reply(status: Int, json: Json, headers: js.Dynamic) {
    def field(path: String*): Json = path.foldLeft(json)((value, name) => value.get(name).getOrElse(Json.Null))
    def code: Json = field("code")
  }

  private def newEnv(): DurableTestKit.Env = {
    val env = DurableTestKit.newEnv()
    env.worker.asInstanceOf[js.Dynamic].ADMIN_PASSWORD = "test"
    env
  }

  private def call(env: DurableTestKit.Env, method: String, path: String, body: js.UndefOr[String] = js.undefined,
    headers: Seq[(String, String)] = Seq(auth, "content-type" -> "application/json") ++ fromAdmin): Future[Reply] = {
    val req = request(base + path, headers, method = method, body = body.map(b => b: js.Any))
    fut[js.Dynamic](AdminHandler.handle(req, env.worker)).flatMap { res =>
      text(res).map(t => Reply(status(res), Json.parse(t).fold(e => fail(s"$t: $e"), identity), res.headers.asInstanceOf[js.Dynamic]))
    }
  }

  private def get(env: DurableTestKit.Env, path: String): Future[Reply] = call(env, "GET", path)
  private def send(env: DurableTestKit.Env, method: String, path: String, value: Json): Future[Reply] = call(env, method, path, Json.render(value))
  private def str(value: String): Json = Json.Str(value)
  private def obj(fields: (String, Json)*): Json = Json.Obj(fields.toList)

  private def settingsBody(reply: Reply, changes: Map[String, SettingValue] = Map.empty, extra: Option[List[String]] = None, head: Option[String] = None,
    settingsEtag: Option[Option[String]] = None): Json = {
    val view = AdminCodec.boardSettings(reply.json).fold(e => fail(e), identity)
    val update = BoardSettingsUpdate(view.values.copy(fields = view.values.fields ++ changes, extra = extra.getOrElse(view.values.extra)),
      head.getOrElse(view.head), settingsEtag.getOrElse(view.settingsEtag), view.headEtag)
    AdminCodec.boardSettingsUpdate(update)
  }

  test("認証が無ければ 401、パスワード未設定なら 503 を JSON で返す") {
    val env = newEnv()
    for {
      unauthorized <- call(env, "GET", "/boards", headers = Nil)
      _ = assertEquals(unauthorized.status, 401)
      _ = assertEquals(unauthorized.code, str("admin:unauthorized"))
      _ = assert(unauthorized.headers.get("www-authenticate").asInstanceOf[String].startsWith("Basic"))
      _ = assertEquals(unauthorized.headers.get("content-type").asInstanceOf[String], "application/json; charset=UTF-8")
      unset <- fut[js.Dynamic](AdminHandler.handle(request(base + "/boards"), js.Dynamic.literal().asInstanceOf[WorkerEnv]))
      unsetText <- text(unset)
    } yield {
      assertEquals(status(unset), 503)
      assertEquals(Json.parse(unsetText).toOption.flatMap(_.get("code")), Some(str("admin:unavailable")))
    }
  }

  test("板の一覧は R2 の接頭辞と bbsmenu.html のリンク先の和集合") {
    val env = newEnv()
    env.putR2("news/SETTING.TXT", "BBS_TITLE=news")
    env.putR2("sandbox/head.txt", "")
    env.putR2("deny.cgi", "")
    env.putR2("bbsmenu.html", """<a href="https://3ch.io/menuonly/">m</a><a href="/news/">n</a>""")
    get(env, "/boards").map { reply =>
      assertEquals(reply.status, 200)
      assertEquals(reply.json, obj("boards" -> Json.Arr(List("menuonly", "news", "sandbox").map(str))))
      assertEquals(reply.headers.get("cache-control").asInstanceOf[String], "no-store")
    }
  }

  test("板設定は明示値と未設定・その他の行・ETag・この板に当てはまる規制ルールを返す") {
    val env = newEnv()
    env.putR2Raw("sample/SETTING.TXT", "BBS_TITLE=板\n# メモ\nBBS_FR_LEVEL=\nUNKNOWN=1\n", "s1")
    env.putR2Raw("sample/head.txt", "<b>案内</b>", "h1")
    env.putR2("deny.cgi", "^sample$<><><>deny\n^other$<><><>deny\n")
    env.putR2("ngwords.cgi", "<><>語<>mark\n")
    get(env, "/boards/sample/settings").map { reply =>
      assertEquals(reply.status, 200)
      assertEquals(reply.field("fields", "BBS_TITLE"), obj("value" -> str("板"), "explicit" -> Json.Bool(true)))
      assertEquals(reply.field("fields", "BBS_FR_LEVEL"), obj("value" -> str(""), "explicit" -> Json.Bool(true)))
      assertEquals(reply.field("fields", "BBS_SAMBA24"), obj("value" -> str("10"), "explicit" -> Json.Bool(false)))
      assertEquals(reply.field("extra"), Json.Arr(List(str("# メモ"), str("UNKNOWN=1"))))
      assertEquals(reply.field("head"), str("<b>案内</b>"))
      assertEquals(reply.field("settingsEtag"), str("s1"))
      assertEquals(reply.field("headEtag"), str("h1"))
      assertEquals(reply.field("rules", "access"), Json.Arr(List(str("^sample$<><><>deny"))))
      assertEquals(reply.field("rules", "ngwords"), Json.Arr(List(str("<><>語<>mark"))))
    }
  }

  test("板設定の保存は行順を保ち、触っていない未設定の項目を書かず、値を入れた項目と明示した空を書く") {
    val env = newEnv()
    env.putR2Raw("sample/SETTING.TXT", "BBS_TITLE=板\n# メモ\nBBS_SAMBA24=30\nUNKNOWN=1\n", "s1")
    for {
      loaded <- get(env, "/boards/sample/settings")
      body = settingsBody(loaded, Map(
        "BBS_TITLE" -> SettingValue("新しい板", explicit = true),
        "BBS_SAMBA24" -> SettingValue("10", explicit = false),
        "BBS_FR_LEVEL" -> SettingValue("", explicit = true),
        "BBS_PHOEBE" -> SettingValue("3", explicit = true)), head = Some("新しい案内"))
      saved <- send(env, "PUT", "/boards/sample/settings", body)
      reloaded <- get(env, "/boards/sample/settings")
    } yield {
      assertEquals(saved.status, 200, saved.json.toString)
      assertEquals(saved.field("partial"), Json.Bool(false))
      assertEquals(env.r2Text("sample/SETTING.TXT"), Some("BBS_TITLE=新しい板\n# メモ\nUNKNOWN=1\nBBS_FR_LEVEL=\nBBS_PHOEBE=3\n"))
      assertEquals(env.r2Text("sample/head.txt"), Some("新しい案内"))
      assertEquals(reloaded.field("settingsEtag"), saved.field("settingsEtag"))
      assertEquals(reloaded.field("headEtag"), saved.field("headEtag"))
      assertEquals(reloaded.field("fields", "BBS_SAMBA24", "explicit"), Json.Bool(false))
    }
  }

  test("未作成の板は既定の SETTING.TXT を読み、変更しなければそのまま作る") {
    val env = newEnv()
    for {
      loaded <- get(env, "/boards/fresh/settings")
      _ = assertEquals(loaded.field("settingsEtag"), Json.Null)
      _ = assertEquals(loaded.field("fields", "BBS_TITLE", "value"), str("fresh＠3ch掲示板"))
      saved <- send(env, "PUT", "/boards/fresh/settings", settingsBody(loaded))
    } yield {
      assertEquals(saved.status, 200)
      assertEquals(env.r2Text("fresh/SETTING.TXT"), Some(io._3ch.common.domain.BbsSettings.defaultText.replace("<DIR_NAME>", "fresh")))
      assertEquals(env.r2Text("fresh/head.txt"), Some(""))
    }
  }

  test("SETTING.TXT が競合したら何も書かず 409、head.txt だけの競合は SETTING.TXT を保存して partial") {
    val env = newEnv()
    env.putR2Raw("sample/SETTING.TXT", "BBS_TITLE=板\n", "s1")
    env.putR2Raw("sample/head.txt", "案内", "h1")
    for {
      loaded <- get(env, "/boards/sample/settings")
      _ = env.putR2Raw("sample/SETTING.TXT", "BBS_TITLE=他人\n", "s2")
      conflict <- send(env, "PUT", "/boards/sample/settings", settingsBody(loaded, Map("BBS_TITLE" -> SettingValue("私", explicit = true)), head = Some("私の案内")))
      _ = assertEquals(conflict.status, 409)
      _ = assertEquals(conflict.code, str("admin:conflict"))
      _ = assertEquals(env.r2Text("sample/SETTING.TXT"), Some("BBS_TITLE=他人\n"))
      _ = assertEquals(env.r2Text("sample/head.txt"), Some("案内"))
      reloaded <- get(env, "/boards/sample/settings")
      _ = env.putR2Raw("sample/head.txt", "他人の案内", "h2")
      partial <- send(env, "PUT", "/boards/sample/settings", settingsBody(reloaded, Map("BBS_TITLE" -> SettingValue("私", explicit = true)), head = Some("私の案内")))
    } yield {
      assertEquals(partial.status, 200)
      assertEquals(partial.field("partial"), Json.Bool(true))
      assertEquals(partial.field("headEtag"), Json.Null)
      assertEquals(env.r2Text("sample/SETTING.TXT"), Some("BBS_TITLE=私\n"))
      assertEquals(env.r2Text("sample/head.txt"), Some("他人の案内"))
    }
  }

  test("読込時に無かった SETTING.TXT が作られていれば競合にする") {
    val env = newEnv()
    for {
      loaded <- get(env, "/boards/fresh/settings")
      _ = env.putR2("fresh/SETTING.TXT", "BBS_TITLE=先\n")
      conflict <- send(env, "PUT", "/boards/fresh/settings", settingsBody(loaded))
    } yield {
      assertEquals(conflict.status, 409)
      assertEquals(env.r2Text("fresh/SETTING.TXT"), Some("BBS_TITLE=先\n"))
    }
  }

  test("板設定の値の誤りは項目ごとに返し、何も書かない") {
    val env = newEnv()
    env.putR2Raw("sample/SETTING.TXT", "BBS_TITLE=板\n", "s1")
    def invalid(changes: Map[String, SettingValue], extra: Option[List[String]] = None): Future[Reply] =
      get(env, "/boards/sample/settings").flatMap(loaded => send(env, "PUT", "/boards/sample/settings", settingsBody(loaded, changes, extra)))
    def explicit(value: String) = SettingValue(value, explicit = true)
    for {
      numbers <- invalid(Map("BBS_SAMBA24" -> explicit("-1"), "BBS_ID_SHIKIBETSU" -> explicit("unknown"), "BBS_FR_LEVEL" -> explicit("high"),
        "BBS_PHOEBE" -> explicit("11"), "BBS_HEISA" -> explicit("on"), "BBS_RENTOU_PASS" -> explicit("everyone")))
      _ = assertEquals(numbers.status, 400)
      _ = assertEquals(numbers.code, str("admin:invalid"))
      _ = assertEquals(numbers.field("fields") match {
        case Json.Obj(entries) => entries.map(_._1).toSet
        case _ => Set.empty[String]
      }, Set("fields.BBS_SAMBA24", "fields.BBS_ID_SHIKIBETSU", "fields.BBS_FR_LEVEL", "fields.BBS_PHOEBE", "fields.BBS_HEISA", "fields.BBS_RENTOU_PASS"))
      lineBreaks <- Future.sequence(List("\r", "\n", "\r\n").map(separator => invalid(Map("BBS_TITLE" -> explicit(s"板${separator}BBS_HEISA=checked")))))
      _ = lineBreaks.foreach { reply =>
        assertEquals(reply.status, 400)
        assertEquals(reply.field("fields", "fields.BBS_TITLE"), str("改行は使用できません。"))
      }
      extraLineBreak <- invalid(Map.empty, Some(List("UNKNOWN=1\rBBS_HEISA=checked")))
      _ = assertEquals(extraLineBreak.field("fields", "extra"), str("改行は使用できません。"))
      duplicated <- invalid(Map.empty, Some(List("BBS_TITLE=二重")))
      _ = assertEquals(duplicated.field("fields", "extra"), str("フォームの項目と同じキーは書けません（BBS_TITLE）。"))
      creation <- invalid(Map("BBS_THREAD_JUNBAN_AS_ONLY" -> explicit("invalid-AS")))
      _ = assertEquals(creation.field("fields", "fields.BBS_THREAD_JUNBAN_AS_ONLY"), str("BBS_THREAD_JUNBAN_AS_ONLY は0以上の整数で指定してください。"))
      legacy <- invalid(Map("BBS_THREAD_TATESUGI" -> explicit("3/86400")))
    } yield {
      assertEquals(legacy.status, 200, "旧形式の件数/秒は受け付ける")
      assertEquals(env.r2Text("sample/SETTING.TXT"), Some("BBS_TITLE=板\nBBS_THREAD_TATESUGI=3/86400\n"))
    }
  }

  test("要求の形・Content-Type・大きさ・パス・メソッドの誤り") {
    val env = newEnv()
    val big = "x" * (AdminApi.MaxBodyBytes + 1)
    for {
      malformed <- call(env, "PUT", "/menu", "{")
      _ = assertEquals((malformed.status, malformed.code), (400, str("admin:invalid")))
      missing <- send(env, "PUT", "/menu", obj("text" -> str("menu")))
      _ = assertEquals(missing.field("fields", "etag"), str("形式が不正です。"))
      unknownField <- send(env, "PUT", "/boards/sample/settings", obj("fields" -> obj("BBS_UNKNOWN" -> obj("value" -> str(""), "explicit" -> Json.Bool(true))),
        "extra" -> Json.Arr(Nil), "head" -> str(""), "settingsEtag" -> Json.Null, "headEtag" -> Json.Null))
      _ = assertEquals(unknownField.field("fields", "fields.BBS_UNKNOWN"), str("形式が不正です。"))
      noType <- call(env, "PUT", "/menu", """{"text":"","etag":null}""", headers = Seq(auth) ++ fromAdmin)
      _ = assertEquals((noType.status, noType.code), (415, str("admin:content-type")))
      formType <- call(env, "PUT", "/menu", """{"text":"","etag":null}""", headers = Seq(auth, "content-type" -> "application/x-www-form-urlencoded") ++ fromAdmin)
      _ = assertEquals(formType.status, 415)
      tooLarge <- send(env, "PUT", "/menu", obj("text" -> str(big), "etag" -> Json.Null))
      _ = assertEquals((tooLarge.status, tooLarge.code), (413, str("admin:too-large")))
      notFound <- get(env, "/unknown")
      _ = assertEquals((notFound.status, notFound.code), (404, str("admin:not-found")))
      unknownRule <- get(env, "/rules/other")
      _ = assertEquals(unknownRule.status, 404)
      method <- call(env, "DELETE", "/menu")
      _ = assertEquals((method.status, method.code), (405, str("admin:method")))
      badBoard <- get(env, "/boards/..%2Fprivate/settings")
      _ = assertEquals((badBoard.status, badBoard.field("fields", "bbs")), (400, str("板名が不正です。")))
    } yield assertEquals(env.r2Text("bbsmenu.html"), None)
  }

  test("規制ルールは検証して条件付きで保存し、誤りは行番号付きで返す") {
    val env = newEnv()
    env.putR2Raw("deny.cgi", "<><><>deny", "d1")
    for {
      loaded <- get(env, "/rules/access")
      _ = assertEquals(loaded.json, obj("text" -> str("<><><>deny"), "etag" -> str("d1")))
      bad <- send(env, "PUT", "/rules/access", obj("text" -> str("# ok\nbad"), "etag" -> str("d1")))
      _ = assertEquals(bad.status, 400)
      _ = assertEquals(bad.field("fields", "text"), str("2行目: 板<>対象<>NGワード<>動作 の形式で入力してください。"))
      others <- Future.sequence(List("<><><>unknown", "<><>[<>deny", "<><><>deny\n<><><>deny<>everyone").map(text =>
        send(env, "PUT", "/rules/access", obj("text" -> str(text), "etag" -> str("d1")))))
      _ = assertEquals(others.map(_.field("fields", "text")), List(str("1行目: 動作が不正です。"), str("1行目: 正規表現が不正です。"),
        str("2行目: 回避レベルは admin・staff・privileged・l10・login のいずれかにしてください。")))
      stale <- send(env, "PUT", "/rules/access", obj("text" -> str(""), "etag" -> str("old")))
      _ = assertEquals((stale.status, stale.code), (409, str("admin:conflict")))
      saved <- send(env, "PUT", "/rules/access", obj("text" -> str("^sample$<><><>deny<>staff"), "etag" -> str("d1")))
      _ = assertEquals(saved.status, 200)
      created <- send(env, "PUT", "/rules/ngwords", obj("text" -> str("<><>語<>deny"), "etag" -> Json.Null))
      _ = assertEquals(created.status, 200)
      again <- send(env, "PUT", "/rules/ngwords", obj("text" -> str(""), "etag" -> Json.Null))
    } yield {
      assertEquals(again.status, 409)
      assertEquals(env.r2Text("deny.cgi"), Some("^sample$<><><>deny<>staff"))
      assertEquals(env.r2Text("ngwords.cgi"), Some("<><>語<>deny"))
      assert(saved.field("etag").isInstanceOf[Json.Str], saved.json.toString)
    }
  }

  test("板一覧は条件付きで保存する") {
    val env = newEnv()
    for {
      empty <- get(env, "/menu")
      _ = assertEquals(empty.json, obj("text" -> str(""), "etag" -> Json.Null))
      created <- send(env, "PUT", "/menu", obj("text" -> str("<a href=\"/news/\">news</a>"), "etag" -> Json.Null))
      _ = assertEquals(created.status, 200)
      stale <- send(env, "PUT", "/menu", obj("text" -> str("other"), "etag" -> Json.Null))
      _ = assertEquals(stale.status, 409)
      updated <- send(env, "PUT", "/menu", obj("text" -> str("menu"), "etag" -> created.field("etag")))
    } yield {
      assertEquals(updated.status, 200)
      assertEquals(env.r2Text("bbsmenu.html"), Some("menu"))
    }
  }

  private val dat = "名無し<>sage<>2026/11/07 ID:x<> 一行目 <br> &lt;b&gt; <>スレタイ\n二人目<><>2026/11/07<> 二 <>\n"

  private def seedThread(env: DurableTestKit.Env): Future[Unit] = for {
    _ <- DurableTestKit.seed(env.threadInstance("sample/1800000000"), "dat" -> dat, "log" -> "1<>IP:192.0.2.1\n", "settings" -> "BBS_TITLE=x<>")
    _ <- env.boardInstance("sample").state.storage.put(js.Dictionary[js.Any]("subjectList" -> "1800000000.dat<>スレタイ (2)\n",
      "removed" -> js.Array("1700000000"))).toFuture
  } yield ()

  test("スレ一覧と詳細を返し、dat の欄と編集用テキストを分けて出す") {
    val env = newEnv()
    for {
      _ <- seedThread(env)
      list <- get(env, "/boards/sample/threads")
      _ = assertEquals(list.json, obj("bbs" -> str("sample"), "subject" -> Json.Arr(List(obj("key" -> str("1800000000"), "line" -> str("1800000000.dat<>スレタイ (2)")))),
        "removed" -> Json.Arr(List(str("1700000000")))))
      detail <- get(env, "/boards/sample/threads/1800000000")
      missing <- get(env, "/boards/sample/threads/1800000009")
      badKey <- get(env, "/boards/sample/threads/123")
    } yield {
      assertEquals(detail.status, 200)
      assertEquals(detail.field("hidden"), Json.Bool(false))
      assertEquals(detail.field("stopped"), Json.Bool(false))
      assertEquals(detail.field("log"), str("1<>IP:192.0.2.1\n"))
      assertEquals(detail.field("legacyLog"), Json.Null)
      val first = detail.field("res") match {
        case Json.Arr(items) => items.head
        case other => fail(other.toString)
      }
      assertEquals(first.get("message"), Some(str(" 一行目 <br> &lt;b&gt; ")))
      assertEquals(first.get("edit").flatMap(_.get("message")), Some(str(" 一行目\n<b> ")))
      assertEquals((missing.status, missing.code), (404, str("admin:not-found")))
      assertEquals((badKey.status, badKey.field("fields", "key")), (400, str("keyは10桁の数字で指定してください。")))
    }
  }

  test("スレの停止・再開・レス編集・レス削除・削除・移動を Thread DO に渡す") {
    val env = newEnv()
    val path = "/boards/sample/threads/1800000000/actions"
    def act(fields: (String, Json)*): Future[Reply] = send(env, "POST", path, obj(fields*))
    for {
      _ <- seedThread(env)
      stopped <- act("action" -> str("stop"))
      _ = assertEquals(stopped.json, obj("ok" -> Json.Bool(true)))
      detail <- get(env, "/boards/sample/threads/1800000000")
      _ = assertEquals(detail.field("stopped"), Json.Bool(true))
      _ <- act("action" -> str("resume"))
      edited <- act("action" -> str("editRes"), "number" -> Json.Num(2), "name" -> str("<script>"), "mail" -> str(""), "message" -> str("一\r\n二"))
      _ = assertEquals(edited.status, 200)
      datText <- env.thread("sample/1800000000").getDat().toFuture
      _ = assert(datText.contains("&lt;script&gt;<><>2026/11/07<>一 <br> 二<>"), datText)
      deleted <- act("action" -> str("deleteRes"), "number" -> Json.Num(1))
      _ = assertEquals(deleted.status, 200)
      outOfRange <- act("action" -> str("deleteRes"), "number" -> Json.Num(999))
      _ = assertEquals((outOfRange.status, outOfRange.code, outOfRange.field("message")), (409, str("admin:thread-state"), str("レス番号が不正です。")))
      zero <- act("action" -> str("deleteRes"), "number" -> Json.Num(0))
      _ = assertEquals((zero.status, zero.field("fields", "number")), (400, str("レス番号は1以上の整数で指定してください。")))
      lineBreak <- act("action" -> str("editRes"), "number" -> Json.Num(2), "name" -> str("名\n前"), "mail" -> str("a\rb"), "message" -> str("一\r二"))
      _ = assertEquals(lineBreak.status, 400)
      _ = assertEquals(lineBreak.field("fields") match {
        case Json.Obj(entries) => entries.map(_._1).toSet
        case _ => Set.empty[String]
      }, Set("name", "mail", "message"))
      badTarget <- act("action" -> str("move"), "targetBoard" -> str("../x"), "targetKey" -> str("1"))
      _ = assertEquals((badTarget.status, badTarget.field("fields", "targetBoard"), badTarget.field("fields", "targetKey")),
        (400, str("板名が不正です。"), str("keyは10桁の数字で指定してください。")))
      sameTarget <- act("action" -> str("move"), "targetBoard" -> str("sample"), "targetKey" -> str("1800000000"))
      _ = assertEquals((sameTarget.status, sameTarget.field("message")), (409, str("移動元と移動先が同じです。")))
      moved <- act("action" -> str("move"), "targetBoard" -> str("other"), "targetKey" -> str("1800000001"))
      _ = assertEquals(moved.status, 200, moved.json.toString)
      source <- get(env, "/boards/sample/threads/1800000000")
      _ = assertEquals((source.field("hidden"), source.field("movedTo")), (Json.Bool(true), str("other/1800000001")))
      target <- get(env, "/boards/other/threads/1800000001")
      _ = assertEquals(target.field("hidden"), Json.Bool(false))
      removed <- send(env, "POST", "/boards/other/threads/1800000001/actions", obj("action" -> str("delete")))
      _ = assertEquals(removed.status, 200)
      list <- get(env, "/boards/other/threads")
      unknown <- act("action" -> str("unknown"))
      missingThread <- send(env, "POST", "/boards/sample/threads/1800000009/actions", obj("action" -> str("stop")))
    } yield {
      assertEquals(list.field("removed"), Json.Arr(List(str("1800000001"))))
      assertEquals((unknown.status, unknown.field("fields", "action")), (400, str("形式が不正です。")))
      assertEquals((missingThread.status, missingThread.field("message")), (409, str("スレッドがありません。")))
    }
  }
}
