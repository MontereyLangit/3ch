package ch3s.bbs.handler

import ch3s.bbs.Main
import japgolly.scalajs.react.facade.React
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
@JSImport("react-dom/server", "renderToStaticMarkup")
private object RenderShellMarkup extends js.Function1[React.Element, String] {
  def apply(element: React.Element): String = js.native
}

class RendererShellSuite extends munit.FunSuite {
  import HandlerTestKit.*

  test("BBSMENU keeps category and board link structure with a client script") {
    val page = BbsMenuPage.render(Seq("sample" -> "日本語 & 案内", "sample2" -> "次の板"), "2026/10/5")
    val res = RendererShell.legacyReact(page).asInstanceOf[js.Dynamic]
    sjisText(res).map { html =>
      assertEquals(header(res, "content-type"), Some("text/html;charset=Shift_JIS"))
      assert(html.contains("<b>３ちゃんねる</b><br/>\n<a href=\"https://www.3chbbs.com/sample/\">日本語 &amp; 案内</a><br/>\n<a href=\"https://www.3chbbs.com/sample2/\">次の板</a><br/>\n"))
      assert(html.contains("charset=Shift_JIS"))
      assert(html.contains("<script type=\"module\" src=\"/static/client.js\"></script>"))
      assert(!html.contains("data-reactroot"))
    }
  }

  test("legacyReact preserves a board title as text and its management link") {
    val res = RendererShell.legacyReact(BoardPage.render("日本語 <板> & 案内", "sample"), doctype = true).asInstanceOf[js.Dynamic]
    sjisText(res).map { html =>
      assert(html.startsWith("<!DOCTYPE html>"))
      assert(html.contains("<title>日本語 &lt;板&gt; &amp; 案内</title>"))
      assert(html.contains("href=\"/admin/board?bbs=sample\""))
      assert(html.contains("nowrap=\"\""))
      assert(html.contains("<script type=\"module\" src=\"/static/client.js\"></script>"))
    }
  }


  test("legacyHtml preserves raw dedicated-browser HTML and Shift_JIS bytes") {
    val html = "<HTML><HEAD><TITLE>日本語</TITLE></HEAD><BODY><!-- 2ch_X:error --><font size=+1 color=#FF0000><b>ERROR: 本文がありません！</b></font>\r\n</BODY></HTML>"
    val res = RendererShell.legacyHtml(html, extra = js.Dictionary("Cache-Control" -> "no-store", "X-Resnum" -> "1")).asInstanceOf[js.Dynamic]
    bytes(res).map { body =>
      assertEquals(status(res), 200)
      assertEquals(body, sjisBytes(html))
      assertEquals(header(res, "content-type"), Some("text/html;charset=Shift_JIS"))
      assertEquals(header(res, "content-length"), Some(body.length.toString))
      assertEquals(header(res, "cache-control"), Some("no-store"))
      assertEquals(header(res, "x-resnum"), Some("1"))
      assertEquals(header(res, "last-modified"), None)
      assertEquals(header(res, "accept-ranges"), None)
    }
  }

  test("legacyHtml retains UTF-8 posting responses without adding the modern shell") {
    val html = "<html><head><meta content=5;URL=/test/read.cgi/sample/1700000000/  http-equiv=refresh></head><body><!-- 2ch_X:true -->書きこみました。</body></html>"
    val res = RendererShell.legacyHtml(html, encoding = "UTF-8", extra = js.Dictionary("X-Threadkey" -> "1700000000")).asInstanceOf[js.Dynamic]
    bytes(res).map { body =>
      assertEquals(status(res), 200)
      assertEquals(body, utf8Bytes(html))
      assertEquals(header(res, "content-type"), Some("text/html;charset=UTF-8"))
      assertEquals(header(res, "content-length"), Some(body.length.toString))
      assertEquals(header(res, "x-threadkey"), Some("1700000000"))
    }
  }

  test("legacyHtml preserves conditional 304 responses and Last-Modified") {
    val modified = new js.Date(1700000000000d)
    val res = RendererShell.legacyHtml("<html>本文</html>", modified = modified, since = modified.toUTCString()).asInstanceOf[js.Dynamic]
    bytes(res).map { body =>
      assertEquals(status(res), 304)
      assertEquals(body, List.empty[Int])
      assertEquals(header(res, "last-modified"), Some(modified.toUTCString()))
      assertEquals(header(res, "content-type"), None)
    }
  }

  test("renderShell renders children and escapes the optional title") {
    val child = React.createElement("main", new js.Object(), "本文 <test>")
    val html = RenderShellMarkup(Main.renderShell(child, "題名 <&>", "/static/client.js"))
    assert(html.startsWith("<html lang=\"ja\">"))
    assert(html.contains("<meta charSet=\"UTF-8\"/>"))
    assert(html.contains("content=\"width=device-width, initial-scale=1.0\""))
    assert(html.contains("<title>題名 &lt;&amp;&gt;</title>"))
    assert(html.contains("<script type=\"module\" src=\"/static/client.js\"></script>"))
    assert(html.contains("<body><main>本文 &lt;test&gt;</main></body>"))
  }

  test("renderShell accepts an omitted title and the development script") {
    val html = RenderShellMarkup(Main.renderShell("本文", js.undefined, "/app/client.ts"))
    assert(!html.contains("<title>"))
    assert(html.contains("src=\"/app/client.ts\""))
    assert(html.contains("<body>本文</body>"))
  }
}
