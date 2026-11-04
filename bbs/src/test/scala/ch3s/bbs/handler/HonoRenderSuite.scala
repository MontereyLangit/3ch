package ch3s.bbs.handler

import ch3s.bbs.Main
import ch3s.bbs.external.hono.{HonoContextFacade, RenderProps}
import ch3s.bbs.external.hono.HonoContextFacade.*
import ch3s.bbs.external.web.{Request, Response}
import japgolly.scalajs.react.facade.React
import japgolly.scalajs.react.vdom.html_<^.*
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.|
import scala.scalajs.js.annotation.JSImport
import scala.scalajs.js.Thenable.Implicits.*

@js.native
@JSImport("hono", "Context")
private class RenderContext(request: Request) extends HonoContextFacade {
  def setRenderer(renderer: js.Function2[React.Element, js.UndefOr[RenderProps], Response | js.Promise[Response]]): Unit = js.native
}

@js.native
private trait ReactRendererProps extends js.Object {
  val children: React.Element = js.native
  val title: js.UndefOr[String] = js.native
}

private type RenderMiddleware = js.Function2[HonoContextFacade, js.Function0[js.Promise[Unit]], js.Promise[Unit]]

@js.native
@JSImport("@hono/react-renderer", "reactRenderer")
private object ReactRendererMiddleware extends js.Function1[js.Function1[ReactRendererProps, React.Element], RenderMiddleware] {
  def apply(component: js.Function1[ReactRendererProps, React.Element]): RenderMiddleware = js.native
}

@js.native
@JSImport("hono", "Hono")
private class RenderApp() extends js.Object {
  def use(path: String, middleware: RenderMiddleware): RenderApp = js.native
  def get(path: String, handler: js.Function1[HonoContextFacade, js.Promise[Response]]): RenderApp = js.native
  def request(path: String): js.Promise[Response] = js.native
  def request(path: String, init: js.Object, env: js.Object): js.Promise[Response] = js.native
}

class HonoRenderSuite extends munit.FunSuite {
  import HandlerTestKit.*

  test("Scala VdomのrawElementとpropsが同期Honoレンダラへそのまま渡る") {
    val context = new RenderContext(request("https://example.test/"))
    val page = <.main("日本語 <&>").rawElement
    val props = new RenderProps { title = "題名" }
    context.setRenderer((element, receivedProps) => {
      assert(element eq page)
      assertEquals(receivedProps.flatMap(_.title).toOption, Some("題名"))
      RendererShell.createSjisResponse(element, "text/html")
    })
    context.renderPromise(page, props).toFuture.flatMap { response =>
      val res = response.asInstanceOf[js.Dynamic]
      bytes(res).map { body =>
        assertEquals(body, sjisBytes("<!DOCTYPE html><main>日本語 &lt;&amp;&gt;</main>"))
        assertEquals(header(res, "content-type"), Some("text/html;charset=Shift_JIS"))
        assertEquals(header(res, "content-length"), Some(body.length.toString))
      }
    }
  }

  test("非同期Honoレンダラはprops省略を受け付け、応答を解決する") {
    val context = new RenderContext(request("https://example.test/"))
    val expected = Responses.response("async")
    context.setRenderer((_, props) => {
      assert(props.isEmpty)
      js.Promise.resolve(expected)
    })
    context.renderPromise(<.main("本文").rawElement).toFuture.map { response =>
      assert(response eq expected)
    }
  }

  test("Scalaハンドラは導入済みReactレンダラミドルウェア経由でUTF-8レイアウトを描画する") {
    val app = new RenderApp()
    app.use("*", ReactRendererMiddleware(props => Main.renderShell(props.children, props.title, "/static/client.js")))
    app.get("/", context => {
      val props = new RenderProps { title = "題名 <&>" }
      context.renderPromise(<.main("日本語 <&>").rawElement, props)
    })
    app.request("/").toFuture.flatMap { response =>
      val res = response.asInstanceOf[js.Dynamic]
      text(res).map { html =>
        assertEquals(status(res), 200)
        assertEquals(header(res, "content-type"), Some("text/html; charset=UTF-8"))
        assert(html.startsWith("<!DOCTYPE html><html lang=\"ja\">"))
        assert(html.contains("<meta charSet=\"UTF-8\"/>"))
        assert(html.contains("<title>題名 &lt;&amp;&gt;</title>"))
        assert(html.contains("<body><main>日本語 &lt;&amp;&gt;</main></body>"))
        assert(html.contains("<script type=\"module\" src=\"/static/client.js\"></script>"))
      }
    }
  }

  test("renderPromiseは非同期レンダラのrejectを伝播する") {
    val context = new RenderContext(request("https://example.test/"))
    val failure = new js.Error("render failed")
    context.setRenderer((_, _) => js.Promise.reject(failure))
    context.renderPromise(<.main("本文").rawElement).toFuture.failed.map {
      case js.JavaScriptException(error) => assert(error == failure)
      case error => fail(s"Unexpected rejection: $error")
    }
  }

  test("createSjisResponseは生文字列を保持し、完全なMIMEタイプを受け付ける") {
    val content = "<!-- 2ch_X:error -->本文 <&>"
    val res = RendererShell.createSjisResponse(content, "application/x-example").asInstanceOf[js.Dynamic]
    bytes(res).map { body =>
      assertEquals(body, sjisBytes(content))
      assertEquals(header(res, "content-type"), Some("application/x-example;charset=Shift_JIS"))
      assertEquals(header(res, "content-length"), Some(body.length.toString))
      assertEquals(status(res), 200)
    }
  }
}
