package ch3s.bbs.handler

import ch3s.bbs.Main
import japgolly.scalajs.react.facade.React
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
@JSImport("react-dom/server", "renderToStaticMarkup")
private object RenderShellMarkup extends js.Function1[React.Element, String] {
  def apply(element: React.Element): String = js.native
}

class RendererShellSuite extends munit.FunSuite {
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
