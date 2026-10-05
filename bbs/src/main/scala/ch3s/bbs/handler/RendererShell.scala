package ch3s.bbs.handler

import japgolly.scalajs.react.facade.React

import scala.scalajs.js

/** HonoX の renderer が呼ぶ HTML ドキュメント全体の組み立て。クライアント JS の URL は TS 側で解決する。 */
object RendererShell {

  def render(children: js.Any, title: js.UndefOr[String], clientScriptSrc: String): React.Element = {
    val meta = React.createElement("meta", js.Dictionary("charSet" -> "UTF-8").asInstanceOf[js.Object])
    val viewport = React.createElement(
      "meta",
      js.Dictionary("name" -> "viewport", "content" -> "width=device-width, initial-scale=1.0").asInstanceOf[js.Object],
    )
    val script = React.createElement("script", js.Dictionary("type" -> "module", "src" -> clientScriptSrc).asInstanceOf[js.Object])
    val headChildren: Seq[React.Node] =
      Seq[React.Node](meta.asInstanceOf[React.Node], viewport.asInstanceOf[React.Node], script.asInstanceOf[React.Node]) ++
        title.toOption.map(t => React.createElement("title", new js.Object(), t).asInstanceOf[React.Node]).toSeq

    val head = React.createElement("head", new js.Object(), headChildren*)
    val body = React.createElement("body", new js.Object(), children.asInstanceOf[React.Node])
    React.createElement("html", js.Dictionary("lang" -> "ja").asInstanceOf[js.Object], head.asInstanceOf[React.Node], body.asInstanceOf[React.Node])
  }
}
