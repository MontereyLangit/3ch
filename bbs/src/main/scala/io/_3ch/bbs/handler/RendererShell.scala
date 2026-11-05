package io._3ch.bbs.handler

import io._3ch.bbs.external.web.Response
import io._3ch.bbs.util.SjisEncoding
import japgolly.scalajs.react.facade.React

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
@JSImport("react-dom/server", "renderToStaticMarkup")
private object renderToStaticMarkup extends js.Function1[React.Element, String] {
  def apply(element: React.Element): String = js.native
}

/** 通常画面の React シェルと、専ブラ互換の既存 HTML 応答を画面側で選択する。 */
object RendererShell {
  /** 完成済み文字列または React 要素を Shift_JIS 応答にする。レイアウトは付けない。 */
  def createSjisResponse(content: String, contentType: String): Response = {
    val bytes = SjisEncoding.encode(content)
    Responses.response(bytes, headers = js.Dictionary(
      "Content-Type" -> s"$contentType;charset=Shift_JIS",
      "Content-Length" -> bytes.length.toString
    ))
  }

  def createSjisResponse(content: React.Element, contentType: String): Response = {
    createSjisResponse(s"<!DOCTYPE html>${renderToStaticMarkup(content)}", contentType)
  }

  // wrangler dev も Vite のビルド済み Static Assets を使う。
  private val ClientScriptSrc = "/static/client.js"

  def clientScript(src: String = ClientScriptSrc): React.Element = {
    React.createElement("script", js.Dictionary("type" -> "module", "src" -> src).asInstanceOf[js.Object])
  }

  /** 専ブラ用 React ページを静的 HTML にし、指定文字コードで返す。通常画面のシェルは付けない。 */
  def legacyReact(
    page: React.Element,
    encoding: String = "Shift_JIS",
    modified: js.Date = new js.Date(),
    since: String = "",
    extra: Seq[(String, String)] = Nil
  ): Response = {
    legacyHtml(renderToStaticMarkup(page), encoding, modified, since, extra)
  }

  /** 完成済み HTML のタグ・コメントを維持して応答する。投稿画面は既存の文字コード判定を渡す。 */
  def legacyHtml(
    html: String,
    encoding: String = "Shift_JIS",
    modified: js.Date = new js.Date(),
    since: String = "",
    extra: Seq[(String, String)] = Nil
  ): Response = {
    Responses.plain(html, "html", encoding, modified = modified, since = since, extra = extra)
  }

  /** UTF-8 の通常画面を組み立てる。クライアント JS の URL は TS 側で解決する。 */
  def render(children: js.Any, title: js.UndefOr[String], clientScriptSrc: String): React.Element = {
    val meta = React.createElement("meta", js.Dictionary("charSet" -> "UTF-8").asInstanceOf[js.Object])
    val viewport = React.createElement(
      "meta",
      js.Dictionary("name" -> "viewport", "content" -> "width=device-width, initial-scale=1.0").asInstanceOf[js.Object],
    )
    val script = clientScript(clientScriptSrc)
    val headChildren: Seq[React.Node] =
      Seq[React.Node](meta.asInstanceOf[React.Node], viewport.asInstanceOf[React.Node],
        React.createElement("link", js.Dictionary("rel" -> "stylesheet", "href" -> "/static/assets/client.css").asInstanceOf[js.Object]).asInstanceOf[React.Node],
        script.asInstanceOf[React.Node]) ++
        title.toOption.map(t => React.createElement("title", new js.Object(), t).asInstanceOf[React.Node]).toSeq

    val head = React.createElement("head", new js.Object(), headChildren*)
    val body = React.createElement("body", new js.Object(), children.asInstanceOf[React.Node])
    React.createElement("html", js.Dictionary("lang" -> "ja").asInstanceOf[js.Object], head.asInstanceOf[React.Node], body.asInstanceOf[React.Node])
  }
}
