package io._3ch.bbs.handler

import io._3ch.bbs.{BuildInfo, Main}
import io._3ch.bbs.durable.DurableTestKit
import io._3ch.bbs.repository.BoardRepository
import io._3ch.common.domain.{BbsSettings, BoardId}
import io._3ch.bbs.handler.HandlerTestKit.*

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

/** 板top (`BoardHandler.index`) と `BoardRepository` のテスト。 */
class BoardHandlerSuite extends munit.FunSuite {

  private def board(value: String): BoardId = BoardId.create(value).toOption.get

  private def render(env: DurableTestKit.Env, path: String) = {
    val app = new RenderApp()
    app.use("*", ReactRendererMiddleware(props => Main.renderShell(props.children, props.title, "/static/client.js")))
    app.get("/:bbs/", context => BoardHandler.index(context))
    app.request(path, js.Dynamic.literal().asInstanceOf[js.Object], env.worker.asInstanceOf[js.Object]).toFuture.flatMap(res => text(res.asInstanceOf[js.Dynamic]))
  }

  test("板top: 板名が[a-zA-Z0-9]{1,16}でなければ404を返し、板名はエスケープして本文へ出す") {
    val env = DurableTestKit.newEnv()
    val app = new RenderApp()
    app.use("*", ReactRendererMiddleware(props => Main.renderShell(props.children, props.title, "/static/client.js")))
    app.get("/:bbs/", context => BoardHandler.index(context))
    def get(path: String) = app.request(path, js.Dynamic.literal().asInstanceOf[js.Object], env.worker.asInstanceOf[js.Object]).toFuture
    for {
      dash <- get("/ab-c/")
      long <- get("/" + "a" * 17 + "/")
      script <- get("/%3Cscript%3E/")
      scriptHtml <- text(script.asInstanceOf[js.Dynamic])
      ok <- get("/" + "a" * 16 + "/")
    } yield {
      assertEquals(status(dash.asInstanceOf[js.Dynamic]), 404)
      assertEquals(status(long.asInstanceOf[js.Dynamic]), 404)
      assertEquals(status(script.asInstanceOf[js.Dynamic]), 404)
      assert(!scriptHtml.contains("<script>"), scriptHtml)
      assert(scriptHtml.contains("https://github.com/MontereyLangit/3ch"), scriptHtml)
      assert(scriptHtml.contains(s"3ch</a> ver ${BuildInfo.version} ${BuildInfo.updatedDate}"), scriptHtml)
      assertEquals(status(ok.asInstanceOf[js.Dynamic]), 200)
    }
  }

  test("getBoardSettings: 板のSETTING.TXTを返し、無ければ既定値を返す") {
    val env = DurableTestKit.newEnv()
    env.putR2("news/SETTING.TXT", "BBS_TITLE=ニュース\n")
    for {
      found <- BoardRepository.getBoardSettings(board("news"), env.worker)
      missing <- BoardRepository.getBoardSettings(board("other"), env.worker)
    } yield {
      assertEquals(found, BbsSettings.create("BBS_TITLE=ニュース\n", board("news")))
      assertEquals(missing, BbsSettings.create(PostingText.settingTxtDefault, board("other")))
    }
  }

  test("getHeadText: 板のhead.txtを返し、無ければ空文字列を返す") {
    val env = DurableTestKit.newEnv()
    env.putR2("news/head.txt", "<b>ルール</b>")
    for {
      found <- BoardRepository.getHeadText(board("news"), env.worker)
      missing <- BoardRepository.getHeadText(board("other"), env.worker)
    } yield {
      assertEquals(found, "<b>ルール</b>")
      assertEquals(missing, "")
    }
  }

  test("板top: BBS_TITLEをtitleとh1に出し、head.txtはエスケープせず埋め込む") {
    val env = DurableTestKit.newEnv()
    env.putR2("news/SETTING.TXT", "BBS_NONAME_NAME=x\nBBS_TITLE=ニュース <板>\n")
    env.putR2("news/head.txt", "<b>rules</b> & <a href=\"/x\">link</a>")
    render(env, "/news/").map { html =>
      assert(html.startsWith("<!DOCTYPE html>"), html)
      assert(html.contains("<title>ニュース &lt;板&gt;</title>"), html)
      assert("<h1[^>]*>ニュース &lt;板&gt;</h1>".r.findFirstIn(html).isDefined, html)
      assert(html.contains("<b>rules</b> & <a href=\"/x\">link</a>"), html)
    }
  }

  test("板top: SETTING.TXTが無ければ既定のタイトルになる") {
    val env = DurableTestKit.newEnv()
    render(env, "/sample/").map { html =>
      assert(html.contains("<title>sample＠3ch掲示板</title>"), html)
      assert("<h1[^>]*>sample＠3ch掲示板</h1>".r.findFirstIn(html).isDefined, html)
    }
  }

  test("板top: IMGUR_CLIENT_ID は設定時だけフォームへ渡す") {
    val env = DurableTestKit.newEnv()
    render(env, "/sample/").flatMap { html =>
      assert(!html.contains("data-imgur-upload"), html)
      env.worker.asInstanceOf[js.Dynamic].updateDynamic("IMGUR_CLIENT_ID")("cid-test")
      render(env, "/sample/").map { withId =>
        assert(withId.contains("data-imgur-client-id=\"cid-test\""), withId)
        assert(withId.contains("data-imgur-upload"), withId)
      }
    }
  }

  test("板top: BBS_TITLE内の<DIR_NAME>を板ディレクトリ名に置換する") {
    val env = DurableTestKit.newEnv()
    env.putR2("abc/SETTING.TXT", "BBS_TITLE=<DIR_NAME>の板\n")
    render(env, "/abc/").map(html => assert(html.contains("<title>abcの板</title>"), html))
  }
}
